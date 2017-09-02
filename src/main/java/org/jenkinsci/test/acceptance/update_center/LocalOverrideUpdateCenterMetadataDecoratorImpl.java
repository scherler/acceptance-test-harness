package org.jenkinsci.test.acceptance.update_center;

import com.cloudbees.sdk.extensibility.Extension;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionScheme;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Allow local plugins specified via environment variables to override plugin metadata from update center.
 *
 * This is to support testing of locally built plugin
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class LocalOverrideUpdateCenterMetadataDecoratorImpl implements UpdateCenterMetadataDecorator {
    @Override
    public void decorate(UpdateCenterMetadata ucm) {
        if ("true".equals(System.getenv("LOCAL_SNAPSHOTS"))) {
            File userHome = new File(System.getProperty("user.home"));
            File localRepo = new File(new File(userHome, ".m2"), "repository");
            VersionScheme versionScheme = new GenericVersionScheme();
            for (Iterator<Map.Entry<String, PluginMetadata>> it = ucm.plugins.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String,PluginMetadata> entry = it.next();
                DefaultArtifact artifact = entry.getValue().getDefaultArtifact();
                File artifactDir = new File(new File(localRepo, artifact.getGroupId().replace('.', File.separatorChar)), artifact.getArtifactId());
                File metadata = new File(artifactDir, "maven-metadata-local.xml");
                if (metadata.isFile()) {
                    try {
                        Version ucVersion = versionScheme.parseVersion(artifact.getVersion());
                        NodeList versions = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metadata).getElementsByTagName("version");
                        for (int i = 0; i < versions.getLength(); i++) {
                            String version = versions.item(i).getTextContent();
                            if (version.endsWith("-SNAPSHOT") && versionScheme.parseVersion(version).compareTo(ucVersion) > 0) {
                                File hpi = new File(new File(artifactDir, version), artifact.getArtifactId() + "-" + version + ".hpi");
                                if (hpi.isFile()) {
                                    String name = entry.getKey();
                                    System.err.println("Overriding " + name + " " + ucVersion + " with local build of " + version);
                                    PluginMetadata m = PluginMetadata.LocalOverride.create(hpi);
                                    String parsedName = m.getName();
                                    if (!name.equals(parsedName)) {
                                        throw new AssertionError("wrong name: " + parsedName + " vs. " + name);
                                    }
                                    entry.setValue(m);
                                }
                            }
                        }
                    } catch (Exception x) {
                        x.printStackTrace();
                    }
                }
            }
        }
        for (Map.Entry<String,String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            if (!isPluginEnvironmentVariable(key))
                continue;

            File file = new File(e.getValue());
            if (!file.exists()) throw new IllegalArgumentException("Plugin file for " + key + " does not exist: " + file.getAbsolutePath());

            PluginMetadata m = PluginMetadata.LocalOverride.create(file);
            PluginMetadata stock = ucm.plugins.get(m.getName());
            if (stock == null) {
                System.err.println("Creating new plugin " + m.getName() + " with local build of " + m.getVersion());
            } else {
                System.err.println("Overriding " + m.getName() + " " + stock.getVersion() + " with local build of " + m.getVersion());
            }
            ucm.plugins.put(m.getName(), m);
        }
    }

    private boolean isPluginEnvironmentVariable(String name) {
        if (name.endsWith(".jpi"))
            return true;
        if (name.endsWith("_JPI"))   // http://stackoverflow.com/a/36992531/12916
            return true;
        return false;
    }
}
