/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.ranger;

import com.google.common.base.Strings;
import io.airlift.log.Logger;
import io.prestosql.ranger.groups.EmptyUserGroups;
import io.prestosql.ranger.groups.UserGroups;
import io.prestosql.ranger.groups.UserGroupsRangerClient;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.security.SystemAccessControl;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

public class RangerPluginInitializer
{
    private static final Logger log = Logger.get(RangerPluginInitializer.class);
    protected static final String RANGER_GROUP_FETCH = "ranger-groups-fetch";

    public SystemAccessControl initRanger(Map<String, String> config)
    {
        RangerConfiguration rangerConfig = RangerConfiguration.getInstance();
        try {
            handleKerberos(rangerConfig, config);
        }
        catch (IOException e) {
            throw new PrestoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Failed to do kerberos right", e);
        }

        List<String> serviceTypes = null;
        List<String> appIDs = null;

        for (final Map.Entry<String, String> configEntry : config.entrySet()) {
            if (configEntry.getKey().startsWith("ranger.")) {
                rangerConfig.set(configEntry.getKey(), configEntry.getValue());
                if (configEntry.getKey().toLowerCase(Locale.ENGLISH).contains("password")) {
                    log.info("Setting: " + configEntry.getKey() + " to: ******");
                }
                else {
                    log.info("Setting: " + configEntry.getKey() + " to: " + configEntry.getValue());
                }
            }
            if (configEntry.getKey().equals("ranger-service-types")) {
                serviceTypes = Arrays.asList(configEntry.getValue().trim().split(","));
            }
            if (configEntry.getKey().equals("ranger-app-ids")) {
                appIDs = Arrays.asList(configEntry.getValue().trim().split(","));
            }

            if (configEntry.getKey().startsWith("ranger-security-config")) {
                try {
                    addRequiredResource(configEntry.getValue().trim());
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (configEntry.getKey().startsWith("ranger-audit-config")) {
                try {
                    addRequiredResource(configEntry.getValue().trim());
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        PrestoAuthorizer authorizer = getPrestoAuthorizer(config, serviceTypes, appIDs);
        return new RangerSystemAccessControl(authorizer, config);
    }

    private void addRequiredResource(final String configFileName)
            throws IOException
    {
        final File configFile = new File(configFileName);
        if (!configFile.exists() || !configFile.canRead()) {
            throw new IOException(configFile + " does not exist, or can not be read from " + configFile.toURI());
        }
        try {
            log.info("Adding ranger config from %s", configFile.toURI().toURL());
            RangerConfiguration.getInstance().addResource(configFile.toURI().toURL());
        }
        catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    private void handleKerberos(RangerConfiguration rangerConfig, Map<String, String> config)
            throws IOException
    {
        String principal = config.get("login-to-ranger-principal");
        String keytab = config.get("login-to-ranger-keytab");
        if (Strings.isNullOrEmpty(principal) || Strings.isNullOrEmpty(keytab)) {
            log.info("Found no kerberos credentials, not communicating with Ranger via Kerberos");
            return;
        }

        rangerConfig.set("hadoop.security.authentication", "kerberos");
        UserGroupInformation.setConfiguration(rangerConfig);
        MiscUtil.authWithKerberos(keytab, principal, null);
    }

    private PrestoAuthorizer getPrestoAuthorizer(Map<String, String> configuration, List<String> serviceTypes,
            List<String> appId)
    {
        final Map<String, RangerPrestoPlugin> plugins = new HashMap();

        IntStream.range(0, serviceTypes.size()).forEachOrdered(i -> {
            synchronized (RangerSystemAccessControlFactory.class) {
                String catalog = serviceTypes.get(i);
                if (null == plugins.get(catalog)) {
                    log.info("Initializing rangerPlugin for serviceType: %s with appId: %s", catalog, appId);
                    RangerPrestoPlugin tmpRangerPlugin = new RangerPrestoPlugin(catalog, appId.get(i));
                    tmpRangerPlugin.init();
                    tmpRangerPlugin.setResultProcessor(new RangerDefaultAuditHandler());
                    plugins.put(catalog, tmpRangerPlugin);
                }
            }
        });

        UserGroups userGroups;
        if (Boolean.parseBoolean(configuration.getOrDefault(RANGER_GROUP_FETCH, "true")) == true) {
            userGroups = new UserGroupsRangerClient(configuration);
        }
        else {
            userGroups = new EmptyUserGroups();
        }
        return new PrestoAuthorizer(userGroups, plugins);
    }
}
