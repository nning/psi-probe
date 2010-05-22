/*
 * Licensed under the GPL License.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * THIS PACKAGE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTIES OF
 * MERCHANTIBILITY AND FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.googlecode.psiprobe.beans;

import com.googlecode.psiprobe.model.ApplicationResource;
import com.googlecode.psiprobe.model.DataSourceInfo;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.management.MBeanServer;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.apache.catalina.Context;
import org.apache.catalina.deploy.ContextResource;
import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.modeler.Registry;
import org.apache.naming.ContextBindings;

public class ResourceResolverBean implements ResourceResolver {

    private Log logger = LogFactory.getLog(getClass());

    public static final String DEFAULT_RESOURCE_PREFIX = "java:comp/env/";

    private List datasourceMappers = new ArrayList();

    public List getApplicationResources() throws NamingException {
        return new ArrayList();
    }

    public synchronized List getApplicationResources(Context context) throws NamingException {

        List resourceList = new ArrayList();

        if (context.getAvailable()) {

            logger.info("Reading CONTEXT " + context.getName());

            boolean contextBound = false;

            try {
                ContextBindings.bindClassLoader(context, null, Thread.currentThread().getContextClassLoader());
                contextBound = true;
            } catch (NamingException e) {
                logger.error("Cannot bind to context. useNaming=false ?");
                logger.debug(e);
            }

            try {
                ContextResource resources[] = context.getNamingResources().findResources();
                for (int i = 0; i < resources.length; i++) {
                    ContextResource contextResource = resources[i];
                    ApplicationResource resource = new ApplicationResource();

                    logger.info("reading resource: " + contextResource.getName());
                    resource.setApplicationName(context.getName());
                    resource.setName(contextResource.getName());
                    resource.setType(contextResource.getType());
                    resource.setScope(contextResource.getScope());
                    resource.setAuth(contextResource.getAuth());
                    resource.setDescription(contextResource.getDescription());

                    lookupResource(resource, contextBound);

                    resourceList.add(resource);
                }

                ContextResourceLink resourceLinks[] = context.getNamingResources().findResourceLinks();
                for (int i = 0; i < resourceLinks.length; i++) {
                    ContextResourceLink link = resourceLinks[i];

                    ApplicationResource resource = new ApplicationResource();
                    logger.debug("reading resourceLink: " + link.getName());
                    resource.setApplicationName(context.getName());
                    resource.setName(link.getName());
                    resource.setType(link.getType());
                    resource.setLinkTo(link.getGlobal());

                    lookupResource(resource, contextBound);
                    resourceList.add(resource);
                }
            } finally {

                if (contextBound) {
                    ContextBindings.unbindClassLoader(context, null, Thread.currentThread().getContextClassLoader());
                }
            }
        }

        return resourceList;
    }

    public void lookupResource(ApplicationResource resource, boolean contextBound) {
        DataSourceInfo dataSourceInfo = null;
        if (contextBound) {
            try {
                Object o = new InitialContext().lookup(DEFAULT_RESOURCE_PREFIX + resource.getName());
                resource.setLookedUp(true);
                for (Iterator it = datasourceMappers.iterator(); it.hasNext();) {
                    DatasourceAccessor accessor = (DatasourceAccessor) it.next();
                    dataSourceInfo = accessor.getInfo(o);
                    if (dataSourceInfo != null) {
                        break;
                    }
                }

            } catch (Throwable e) {
                resource.setLookedUp(false);
                dataSourceInfo = null;
                logger.error("Failed to lookup: " + resource.getName(), e);
                //
                // make sure we always re-throw ThreadDeath
                //
                if (e instanceof ThreadDeath) {
                    throw (ThreadDeath) e;
                }
            }
        } else {
            resource.setLookedUp(false);
        }

        //
        // Tomcat 5.0.x DBCP datasources would have URL set to null if they incorrectly configured
        // so we need to deal with this little feature
        //
        if (dataSourceInfo != null && dataSourceInfo.getJdbcURL() == null) {
            resource.setLookedUp(false);
        }

        if (resource.isLookedUp() && dataSourceInfo != null) {
            resource.setDataSourceInfo(dataSourceInfo);
        }
    }

    public boolean resetResource(Context context, String resourceName) throws NamingException {

        boolean reset = false;

        ContextBindings.bindClassLoader(context, null, Thread.currentThread().getContextClassLoader());
        try {
            Object o = new InitialContext().lookup(ResourceResolverBean.DEFAULT_RESOURCE_PREFIX + resourceName);
            try {
                for (Iterator it = datasourceMappers.iterator(); it.hasNext();) {
                    DatasourceAccessor accessor = (DatasourceAccessor) it.next();
                    if (accessor.canMap(o)) {
                        accessor.reset(o);
                        reset = true;
                        break;
                    }
                }
            } catch (Throwable e) {
                reset = false;
                //
                // make sure we always re-throw ThreadDeath
                //
                if (e instanceof ThreadDeath) {
                    throw (ThreadDeath) e;
                }
            }
        } finally {
            ContextBindings.unbindClassLoader(context, null, Thread.currentThread().getContextClassLoader());
        }

        return reset;
    }

    public DataSource lookupDataSource(Context context, String resourceName) throws NamingException {
        ContextBindings.bindClassLoader(context, null, Thread.currentThread().getContextClassLoader());

        try {
            Object o = new InitialContext().lookup(ResourceResolverBean.DEFAULT_RESOURCE_PREFIX + resourceName);

            if (o instanceof DataSource) {
                return (DataSource) o;
            } else {
                return null;
            }
        } finally {
            ContextBindings.unbindClassLoader(context, null, Thread.currentThread().getContextClassLoader());
        }
    }

    public List getDatasourceMappers() {
        return datasourceMappers;
    }

    public void setDatasourceMappers(List datasourceMappers) {
        this.datasourceMappers = datasourceMappers;
    }

    public boolean supportsPrivateResources() {
        return true;
    }

    public MBeanServer getMBeanServer() {
        return new Registry().getMBeanServer();
    }

}