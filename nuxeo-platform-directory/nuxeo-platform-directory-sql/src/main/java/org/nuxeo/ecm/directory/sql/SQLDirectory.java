/*
 * (C) Copyright 2006-2011 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     George Lefter
 *     Florent Guillaume
 */
package org.nuxeo.ecm.directory.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.ColumnType;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Column;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table;
import org.nuxeo.ecm.core.storage.sql.jdbc.dialect.Dialect;
import org.nuxeo.ecm.directory.AbstractDirectory;
import org.nuxeo.ecm.directory.Directory;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.DirectoryServiceImpl;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.runtime.RuntimeService;
import org.nuxeo.runtime.api.ConnectionHelper;
import org.nuxeo.runtime.api.DataSourceHelper;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.metrics.MetricsService;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

public class SQLDirectory extends AbstractDirectory {

    protected class TxSessionCleaner implements Synchronization {
        private final SQLSession session;

        Throwable initContext = captureInitContext();

        protected TxSessionCleaner(SQLSession session) {
            this.session = session;
        }

        protected Throwable captureInitContext() {
            if (!log.isDebugEnabled()) {
                return null;
            }
            return new Throwable("SQL directory session init context in "
                    + SQLDirectory.this);
        }

        protected void checkIsNotLive() {
            try {
                if (!session.isLive()) {
                    return;
                }
                if (initContext != null) {
                    log.warn("Closing a sql directory session for you "
                            + session, initContext);
                } else {
                    log.warn("Closing a sql directory session for you "
                            + session);
                }
                if (!TransactionHelper.isTransactionActiveOrMarkedRollback()) {
                    log.warn("Closing sql directory session outside a transaction"
                            + session);
                }
                session.close();
            } catch (DirectoryException e) {
                log.error(
                        "Cannot state on sql directory session before commit "
                                + SQLDirectory.this, e);
            }

        }

        @Override
        public void beforeCompletion() {
            checkIsNotLive();
        }

        @Override
        public void afterCompletion(int status) {
            checkIsNotLive();
        }

    }

    private static final Log log = LogFactory.getLog(SQLDirectory.class);

    public static final String TENANT_ID_FIELD = "tenantId";

    /**
     * Maximum number of times we retry a connection if the server says it's
     * overloaded.
     */
    public static final int MAX_CONNECTION_TRIES = 5;

    private final SQLDirectoryDescriptor config;

    private final boolean nativeCase;

    private boolean managedSQLSession;

    private DataSource dataSource;

    private List<Session> sessions = new ArrayList<Session>();

    private final Table table;

    private final Schema schema;

    private final Map<String, Field> schemaFieldMap;

    private final List<String> storedFieldNames;

    private final Dialect dialect;

    // @since 5.7
    protected final MetricRegistry registry = SharedMetricRegistries.getOrCreate(MetricsService.class.getName());

    protected final Counter sessionCount = registry.counter(MetricRegistry.name(
            SQLDirectory.class, "session"));

    protected final Counter sessionMaxCount = registry.counter(MetricRegistry.name(
            SQLDirectory.class, "session-max"));

    public SQLDirectory(SQLDirectoryDescriptor config) throws ClientException {
        this.config = config;
        nativeCase = Boolean.TRUE.equals(config.nativeCase);

        // register the references to other directories
        addReferences(config.getInverseReferences());
        addReferences(config.getTableReferences());

        // cache parameterization
        cache.setMaxSize(config.getCacheMaxSize());
        cache.setTimeout(config.getCacheTimeout());

        Connection sqlConnection = getConnection();
        try {
            dialect = Dialect.createDialect(sqlConnection, null, null);

            if (config.initDependencies != null) {
                // initialize dependent directories first
                final RuntimeService runtime = Framework.getRuntime();
                DirectoryServiceImpl directoryService = (DirectoryServiceImpl) runtime.getComponent(DirectoryService.NAME);
                for (String dependency : config.initDependencies) {
                    log.debug("initializing dependencies first: " + dependency);
                    Directory dir = directoryService.getDirectory(dependency);
                    dir.getName();
                }
            }
            // setup table and fields maps
            table = SQLHelper.addTable(config.tableName, dialect,
                    useNativeCase());
            SchemaManager schemaManager = Framework.getLocalService(SchemaManager.class);
            schema = schemaManager.getSchema(config.schemaName);
            if (schema == null) {
                throw new DirectoryException("schema not found: "
                        + config.schemaName);
            }
            schemaFieldMap = new LinkedHashMap<String, Field>();
            storedFieldNames = new LinkedList<String>();
            boolean hasPrimary = false;
            for (Field f : schema.getFields()) {
                String fieldName = f.getName().getLocalName();
                schemaFieldMap.put(fieldName, f);

                if (!isReference(fieldName)) {
                    // list of fields that are actually stored in the table of
                    // the current directory and not read from an external
                    // reference
                    storedFieldNames.add(fieldName);

                    boolean isId = fieldName.equals(config.getIdField());
                    ColumnType type = ColumnType.fromField(f);
                    if (isId && config.isAutoincrementIdField()) {
                        type = ColumnType.AUTOINC;
                    }
                    Column column = SQLHelper.addColumn(table, fieldName, type,
                            useNativeCase());
                    if (isId) {
                        if (config.isAutoincrementIdField()) {
                            column.setIdentity(true);
                        }
                        column.setPrimary(true);
                        column.setNullable(false);
                        hasPrimary = true;
                    }
                    Object defaultValue = f.getDefaultValue();
                    if (defaultValue != null) {
                        column.setDefaultValue(defaultValue.toString());
                    }
                }
            }
            if (!hasPrimary) {
                throw new DirectoryException(
                        String.format(
                                "Directory '%s' id field '%s' is not present in schema '%s'",
                                getName(), getIdField(), getSchema()));
            }

            SQLHelper helper = new SQLHelper(sqlConnection, table,
                    config.dataFileName,
                    config.getDataFileCharacterSeparator(),
                    config.createTablePolicy);
            helper.setupTable();

            try {
                if (config.dataSourceName == null) {
                    sqlConnection.commit();
                }
            } catch (SQLException e) {
                throw new DirectoryException(e);
            }
        } catch (StorageException e) {
            throw new DirectoryException(e);
        } finally {
            try {
                sqlConnection.close();
            } catch (Exception e) {
                throw new DirectoryException(e);
            }
        }
    }

    public SQLDirectoryDescriptor getConfig() {
        // utility method to simplify testing
        return config;
    }

    /** DO NOT USE, use getConnection() instead. */
    protected DataSource getDataSource() throws DirectoryException {
        if (dataSource != null) {
            return dataSource;
        }
        try {
            if (config.dataSourceName != null) {
                managedSQLSession = true;
                dataSource = DataSourceHelper.getDataSource(config.dataSourceName);
                // InitialContext context = new InitialContext();
                // dataSource = (DataSource)
                // context.lookup(config.dataSourceName);
            } else {
                managedSQLSession = false;
                dataSource = new SimpleDataSource(config.dbUrl,
                        config.dbDriver, config.dbUser, config.dbPassword);
            }
            log.trace("found datasource: " + dataSource);
            return dataSource;
        } catch (Exception e) {
            log.error("dataSource lookup failed", e);
            throw new DirectoryException("dataSource lookup failed", e);
        }
    }

    public Connection getConnection() throws DirectoryException {
        try {
            // try single-datasource non-XA mode
            Connection connection = ConnectionHelper.getConnection(config.dataSourceName);
            if (connection == null) {
                // standard datasource usage
                connection = getConnection(getDataSource());
            } else {
                managedSQLSession = true;
            }
            return connection;
        } catch (SQLException e) {
            throw new DirectoryException("Cannot connect to SQL directory '"
                    + getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Gets a physical connection from a datasource.
     * <p>
     * A few retries are done to work around databases that have problems with
     * many open/close in a row.
     *
     * @param dataSource the datasource
     * @return the connection
     */
    protected Connection getConnection(DataSource dataSource)
            throws SQLException {
        for (int tryNo = 0;; tryNo++) {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                if (tryNo >= MAX_CONNECTION_TRIES) {
                    throw e;
                }
                if (e.getErrorCode() != 12519) {
                    throw e;
                }
                // Oracle: Listener refused the connection with the
                // following error: ORA-12519, TNS:no appropriate
                // service handler found SQLState = "66000"
                // Happens when connections are open too fast (unit tests)
                // -> retry a few times after a small delay
                if (log.isDebugEnabled()) {
                    log.debug(String.format(
                            "Connections open too fast, retrying in %ds: %s",
                            Integer.valueOf(tryNo),
                            e.getMessage().replace("\n", " ")));
                }
                try {
                    Thread.sleep(1000 * tryNo);
                } catch (InterruptedException ie) {
                    // restore interrupted status
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted");
                }
            }
        }
    }

    @Override
    public String getName() {
        return config.getName();
    }

    @Override
    public String getSchema() {
        return config.getSchemaName();
    }

    @Override
    public String getParentDirectory() {
        return config.getParentDirectory();
    }

    @Override
    public String getIdField() {
        return config.getIdField();
    }

    @Override
    public String getPasswordField() {
        return config.getPasswordField();
    }

    @Override
    public synchronized Session getSession() throws DirectoryException {
        SQLSession session = new SQLSession(this, config, managedSQLSession);
        addSession(session);
        return session;
    }

    protected synchronized void addSession(final SQLSession session)
            throws DirectoryException {
        sessions.add(session);
        sessionCount.inc();
        if (sessionCount.getCount() > sessionMaxCount.getCount()) {
            sessionMaxCount.inc();
        }
        registerInTx(session);
    }

    protected void registerInTx(final SQLSession session)
            throws DirectoryException {
        if (!TransactionHelper.isTransactionActive()) {
            return;
        }
        TransactionManager tm;
        try {
            tm = TransactionHelper.lookupTransactionManager();
            Transaction tx = tm.getTransaction();
            tx.registerSynchronization(new TxSessionCleaner(session));
        } catch (NamingException | SystemException | IllegalStateException
                | RollbackException e) {
            throw new DirectoryException(
                    "Cannot register in tx for session cleanup handling "
                            + this, e);
        }
    }

    protected synchronized void removeSession(Session session) {
        if (sessions.remove(session)) {
            // called from session.close()
            sessionCount.dec();
        }
    }

    @Override
    public synchronized void shutdown() {
        if (sessions.isEmpty()) {
            return;
        }
        List<Session> lastSessions = sessions;
        sessions = new ArrayList<Session>();
        for (Session session : lastSessions) {
            try {
                session.close();
            } catch (DirectoryException e) {
                log.error("Error during shutdown of directory '" + getName()
                        + "'", e);
            }
        }
    }

    public Map<String, Field> getSchemaFieldMap() {
        return schemaFieldMap;
    }

    public List<String> getStoredFieldNames() {
        return storedFieldNames;
    }

    public Table getTable() {
        return table;
    }

    public Dialect getDialect() {
        return dialect;
    }

    public boolean useNativeCase() {
        return nativeCase;
    }

    @Override
    public boolean isMultiTenant() {
        return table.getColumn(TENANT_ID_FIELD) != null;
    }

    @Override
    public String toString() {
        return "SQLDirectory [name=" + config.name + "]";
    }

}
