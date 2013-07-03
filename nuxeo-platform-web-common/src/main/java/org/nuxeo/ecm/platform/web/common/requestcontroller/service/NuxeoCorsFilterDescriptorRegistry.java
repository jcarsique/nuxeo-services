package org.nuxeo.ecm.platform.web.common.requestcontroller.service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.runtime.model.ContributionFragmentRegistry;

/**
 * Nuxeo Cors filter description registry model.
 *
 * @author <a href="mailto:ak@nuxeo.com">Arnaud Kervern</a>
 * @since 5.7.2
 */
public class NuxeoCorsFilterDescriptorRegistry extends
        ContributionFragmentRegistry<NuxeoCorsFilterDescriptor> {

    protected Map<String, NuxeoCorsFilterDescriptor> descs = new HashMap<>();

    @Override
    public String getContributionId(NuxeoCorsFilterDescriptor contrib) {
        return contrib.name;
    }

    @Override
    public void contributionUpdated(String id, NuxeoCorsFilterDescriptor contrib, NuxeoCorsFilterDescriptor newOrigContrib) {
        if (descs.containsKey(id)) {
            descs.remove(id);
        }

        if (contrib.enabled) {
            descs.put(id, contrib);
        }
    }

    @Override
    public void contributionRemoved(String id, NuxeoCorsFilterDescriptor origContrib) {
        if (descs.containsKey(id)) {
            descs.remove(id);
        }
    }

    @Override
    public NuxeoCorsFilterDescriptor clone(NuxeoCorsFilterDescriptor orig) {
        try {
            return orig.clone();
        } catch (CloneNotSupportedException e) {
            // Should never happens...
            return null;
        }
    }

    @Override
    public void merge(NuxeoCorsFilterDescriptor src, NuxeoCorsFilterDescriptor dst) {
        dst.merge(src);
    }

    public NuxeoCorsFilterDescriptor getFirstMatchingDescriptor(String uri) {
        for (NuxeoCorsFilterDescriptor filterDesc : descs.values()) {
            Pattern pattern = Pattern.compile(filterDesc.pattern);
            if (pattern.matcher(uri).matches()) {
                return filterDesc;
            }
        }
        return null;
    }
}
