/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.pde.build.internal.tests;

import java.io.*;
import java.util.*;
import java.util.jar.Attributes;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.Target;
import org.apache.tools.ant.types.Path;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.pde.build.tests.BuildConfiguration;
import org.eclipse.pde.build.tests.PDETestCase;
import org.eclipse.pde.internal.build.*;
import org.eclipse.pde.internal.build.site.BuildTimeSiteFactory;
import org.eclipse.update.core.IIncludedFeatureReference;
import org.eclipse.update.core.model.FeatureModel;
import org.eclipse.update.core.model.FeatureModelFactory;

public class ScriptGenerationTests extends PDETestCase {

	// Test that script generation works when buildDirectory does not contain a plugins subdirectory
	public void testBug147292() throws Exception {
		IFolder buildFolder = newTest("147292");

		String bundleId = "org.eclipse.pde.build.test.147292";
		Utils.generateBundle(buildFolder, bundleId);

		//getScriptGenerationProperties sets buildDirectory to buildFolder by default
		Properties properties = BuildConfiguration.getScriptGenerationProperties(buildFolder, "plugin", bundleId);
		properties.put("baseLocation", buildFolder.getLocation().toOSString());
		generateScripts(buildFolder, properties);

		// test passes if generateScripts did not throw an exception 
		assertResourceFile(buildFolder, "build.xml");
	}

	// Test that the order in which archivesFormat and configInfo are set does not matter
	public void testBug148288() throws Exception {
		IProject buildProject = newTest();

		class MyBuildScriptGenerator extends BuildScriptGenerator {
			public HashMap getArchivesFormat() {
				return super.getArchivesFormat();
			}
		};

		String location = buildProject.getLocation().toOSString();
		MyBuildScriptGenerator generator = new MyBuildScriptGenerator();
		generator.setElements(new String[] {});
		generator.setWorkingDirectory(location);
		BuildTimeSiteFactory.setInstalledBaseSite(location);
		AbstractScriptGenerator.setConfigInfo("win32, win32, x86");
		generator.setArchivesFormat("win32, win32, x86 - antZip");
		generator.generate();

		HashMap map = generator.getArchivesFormat();
		assertEquals(map.size(), 1);
		Config config = (Config) map.keySet().iterator().next();
		assertEquals(map.get(config), "antZip");

		clearStatics();

		generator = new MyBuildScriptGenerator();
		generator.setElements(new String[] {});
		generator.setWorkingDirectory(location);
		BuildTimeSiteFactory.setInstalledBaseSite(location);
		generator.setArchivesFormat("win32, win32, x86 - folder");
		AbstractScriptGenerator.setConfigInfo("win32, win32, x86");
		generator.generate();

		map = generator.getArchivesFormat();
		assertEquals(map.size(), 1);
		config = (Config) map.keySet().iterator().next();
		assertEquals(map.get(config), "folder");
	}

	// Test script generation for bundles using Bundle-RequiredExecutionEnvironment
	// when the state does not contain org.eclipse.osgi
	public void testBug178447() throws Exception {
		IFolder buildFolder = newTest("178447");

		String bundleId = "org.eclipse.pde.build.test.178447";
		Attributes manifestAdditions = new Attributes();
		manifestAdditions.put(new Attributes.Name("Bundle-RequiredExecutionEnvironment"), "J2SE-1.3");
		Utils.generateBundleManifest(buildFolder, bundleId, "1.0.0", manifestAdditions);
		Utils.generatePluginBuildProperties(buildFolder, null);

		Properties properties = BuildConfiguration.getScriptGenerationProperties(buildFolder, "plugin", bundleId);
		properties.put("baseLocation", buildFolder.getLocation().toOSString());
		generateScripts(buildFolder, properties);

		// test passes if generateScripts did not throw an exception 
		assertResourceFile(buildFolder, "build.xml");
	}

	// Test the use of customBuildCallbacks.buildpath
	public void testBug183869() throws Exception {
		IFolder buildFolder = newTest("183869");

		Utils.generateAllElements(buildFolder, "a.feature");

		Properties buildProperties = BuildConfiguration.getBuilderProperties(buildFolder);
		Utils.storeBuildProperties(buildFolder, buildProperties);

		runBuild(buildFolder);

		assertResourceFile(buildFolder, "log.log");
		String[] lines = new String[] {"[echo] Hello Plugin!", "[echo] Hello Feature!"};
		assertLogContainsLines(buildFolder.getFile("log.log"), lines);
	}

	// test platform.xml
	public void testBug183924() throws Exception {
		IFolder buildFolder = newTest("183924");
		IFolder configFolder = Utils.createFolder(buildFolder, "configuration/org.eclipse.update");

		//Figure out the version of the org.eclipse.rcp feature
		String baseLocation = Platform.getInstallLocation().getURL().getPath();
		File features = new File(baseLocation, "features");
		FileFilter filter = new FileFilter() {
			public boolean accept(File pathname) {
				return pathname.getName().startsWith("org.eclipse.rcp_");
			}
		};
		File rcp[] = features.listFiles(filter);
		assertTrue(rcp.length > 0);
		String name = rcp[0].getName();
		String version = name.substring("org.eclipse.rcp_".length(), name.length());

		// copy platform.xml and set the baseLocation and rcp version
		IFile sourceXML = buildFolder.getFile("platform.xml");
		Map replacements = new HashMap();
		replacements.put("BASE_LOCATION", baseLocation);
		replacements.put("RCP_VERSION", version);
		Utils.transferAndReplace(sourceXML.getLocationURI().toURL(), configFolder.getFile("platform.xml"), replacements);

		//Generate Scripts for org.eclipse.rcp, expect to find it through platform.xml
		Properties properties = BuildConfiguration.getScriptGenerationProperties(buildFolder, "feature", "org.eclipse.rcp");
		properties.put("baseLocation", buildFolder.getLocation().toOSString());
		generateScripts(buildFolder, properties);

		//platform.xml has MANAGED-ONLY policy, expect to not find org.eclipse.core.resources
		properties = BuildConfiguration.getScriptGenerationProperties(buildFolder, "plugin", "org.eclipse.core.resources");
		properties.put("baseLocation", buildFolder.getLocation().toOSString());
		try {
			//this is expected to fail
			generateScripts(buildFolder, properties);
			assertTrue(false);
		} catch (Exception e) {
			assertTrue(e.getMessage().endsWith("Unable to find element: org.eclipse.core.resources."));
		}
	}

	// test that the order of features passed to FeatureGenerator is preserved
	public void testBug187809() throws Exception {
		IFolder buildFolder = newTest("187809");

		Utils.generateFeature(buildFolder, "sdk", new String[] {"foo", "bar", "disco"}, null);

		assertResourceFile(buildFolder, "features/sdk/feature.xml");
		IFile feature = buildFolder.getFile("features/sdk/feature.xml");
		FeatureModelFactory factory = new FeatureModelFactory();
		InputStream stream = new BufferedInputStream(feature.getLocationURI().toURL().openStream());
		FeatureModel model = null;
		try {
			model = factory.parseFeature(stream);
		} finally {
			stream.close();
		}
		IIncludedFeatureReference[] included = model.getFeatureIncluded();
		assertEquals(included.length, 3);
		assertEquals(included[0].getVersionedIdentifier().getIdentifier(), "foo");
		assertEquals(included[1].getVersionedIdentifier().getIdentifier(), "bar");
		assertEquals(included[2].getVersionedIdentifier().getIdentifier(), "disco");
	}

	// Test that & characters in classpath are escaped properly
	public void testBug125577() throws Exception {
		IFolder buildFolder = newTest("125577");
		Utils.createFolder(buildFolder, "plugins");

		//Create Bundle A
		IFolder bundleA = buildFolder.getFolder("plugins/A & A");
		bundleA.create(true, true, null);
		Utils.generateBundle(bundleA, "A");

		//Create Bundle B
		IFolder bundleB = buildFolder.getFolder("plugins/B");
		bundleB.create(true, true, null);
		Utils.generatePluginBuildProperties(bundleB, null);

		// Bundle B requires Bundle A
		Attributes manifestAdditions = new Attributes();
		manifestAdditions.put(new Attributes.Name("Require-Bundle"), "A");
		Utils.generateBundleManifest(bundleB, "B", "1.0.0", manifestAdditions);

		generateScripts(buildFolder, BuildConfiguration.getScriptGenerationProperties(buildFolder, "plugin", "B"));
		
		assertResourceFile(bundleB, "build.xml");
		//if the & was not escaped, it won't be a valid ant script
		assertValidAntScript(bundleB.getFile("build.xml"));
	}

	public void testSimpleClasspath() throws Exception {
		IFolder buildFolder = newTest("SimpleClasspath");

		Utils.generatePluginBuildProperties(buildFolder, null);
		Attributes manifestAdditions = new Attributes();
		manifestAdditions.put(new Attributes.Name("Require-Bundle"), "org.eclipse.equinox.preferences");
		Utils.generateBundleManifest(buildFolder, "bundle", "1.0.0", manifestAdditions);

		generateScripts(buildFolder, BuildConfiguration.getScriptGenerationProperties(buildFolder, "plugin", "bundle"));

		IFile buildScript = buildFolder.getFile("build.xml");
		Project antProject = assertValidAntScript(buildScript);
		Target dot = (Target) antProject.getTargets().get("@dot");
		assertNotNull(dot);
		Object child = AntUtils.getFirstChildByName(dot, "path");
		assertNotNull(child);
		assertTrue(child instanceof Path);
		String path = child.toString();
		
		//Assert classpath has correct contents
		int idx[] = {0, path.indexOf("org.eclipse.equinox.preferences"), path.indexOf("org.eclipse.osgi"), path.indexOf("org.eclipse.equinox.common"), path.indexOf("org.eclipse.equinox.registry"), path.indexOf("org.eclipse.core.jobs")};
		for (int i = 0; i < idx.length - 1; i++) {
			assertTrue(idx[i] < idx[i + 1]);
		}
	}
}
