/*******************************************************************************
 * Copyright (c) 2005, 2013, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Johannes Ahlers <Johannes.Ahlers@gmx.de> - bug 477677
*******************************************************************************/
package org.eclipse.pde.internal.ui.search.dependencies;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.search.*;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.util.ManifestElement;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.core.build.IBuild;
import org.eclipse.pde.core.build.IBuildEntry;
import org.eclipse.pde.core.plugin.*;
import org.eclipse.pde.internal.build.IPDEBuildConstants;
import org.eclipse.pde.internal.core.ICoreConstants;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.build.WorkspaceBuildModel;
import org.eclipse.pde.internal.core.bundle.BundlePluginBase;
import org.eclipse.pde.internal.core.ibundle.*;
import org.eclipse.pde.internal.core.plugin.PluginImport;
import org.eclipse.pde.internal.core.project.PDEProject;
import org.eclipse.pde.internal.core.search.PluginJavaSearchUtil;
import org.eclipse.pde.internal.core.text.bundle.*;
import org.eclipse.pde.internal.core.util.ManifestUtils;
import org.eclipse.pde.internal.ui.PDEPlugin;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.osgi.framework.*;

public class AddNewDependenciesOperation extends WorkspaceModifyOperation {

	protected IProject fProject;
	protected IBundlePluginModelBase fBase;
	private boolean fNewDependencies = false;

	protected static class ReferenceFinder extends SearchRequestor {
		private boolean found = false;

		@Override
		public void acceptSearchMatch(SearchMatch match) throws CoreException {
			found = true;
		}

		public boolean foundMatches() {
			return found;
		}
	}

	public AddNewDependenciesOperation(IProject project, IBundlePluginModelBase base) {
		fProject = project;
		fBase = base;
	}

	@Override
	protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException, InterruptedException {
		SubMonitor subMonitor = SubMonitor.convert(monitor, PDEUIMessages.AddNewDependenciesOperation_mainTask, 100);
		final IBundle bundle = fBase.getBundleModel().getBundle();
		final Set<String> ignorePkgs = new HashSet<>();
		final String[] secDeps = findSecondaryBundles(bundle, ignorePkgs);
		if (secDeps == null || secDeps.length == 0) {
			return;
		}
		subMonitor.worked(4);
		findImportPackages(bundle, ignorePkgs);
		subMonitor.worked(2);
		addProjectPackages(bundle, ignorePkgs);
		subMonitor.worked(4);

		final Map<ExportPackageDescription, String> additionalDeps = new HashMap<>();
		subMonitor.subTask(PDEUIMessages.AddNewDependenciesOperation_searchProject);

		boolean useRequireBundle = new ProjectScope(fProject).getNode(PDECore.PLUGIN_ID).getBoolean(ICoreConstants.RESOLVE_WITH_REQUIRE_BUNDLE, true);
		findSecondaryDependencies(secDeps, ignorePkgs, additionalDeps, bundle, useRequireBundle,
				subMonitor.newChild(80));
		handleNewDependencies(additionalDeps, useRequireBundle, subMonitor.newChild(10));
	}

	public boolean foundNewDependencies() {
		return fNewDependencies;
	}

	protected String[] findSecondaryBundles(IBundle bundle, Set<String> ignorePkgs) {
		String[] secDeps = getSecondaryDependencies();
		if (secDeps == null)
			return null;
		Set<String> manifestPlugins = findManifestPlugins(bundle, ignorePkgs);

		List<String> result = new LinkedList<>();
		for (int i = 0; i < secDeps.length; i++)
			if (!manifestPlugins.contains(secDeps[i]))
				result.add(secDeps[i]);

		return result.toArray(new String[result.size()]);
	}

	private String[] getSecondaryDependencies() {
		IBuild build = getBuild();
		if (build != null) {
			IBuildEntry be = build.getEntry(IBuildEntry.SECONDARY_DEPENDENCIES);
			if (be != null)
				return be.getTokens();
		}
		return null;
	}

	protected final IBuild getBuild() {
		IFile buildProps = PDEProject.getBuildProperties(fProject);
		if (buildProps != null) {
			WorkspaceBuildModel model = new WorkspaceBuildModel(buildProps);
			return model.getBuild();
		}
		return null;
	}

	private Set<String> findManifestPlugins(IBundle bundle, Set<String> ignorePkgs) {
		IManifestHeader header = bundle.getManifestHeader(Constants.REQUIRE_BUNDLE);
		if (header == null)
			return new HashSet<>(0);
		Set<String> plugins = (header instanceof RequireBundleHeader) ? findManifestPlugins((RequireBundleHeader) header, ignorePkgs) : findManifestPlugins(ignorePkgs);
		if (plugins.contains(IPDEBuildConstants.BUNDLE_CORE_RUNTIME))
			plugins.add("system.bundle"); //$NON-NLS-1$
		return plugins;
	}

	private Set<String> findManifestPlugins(RequireBundleHeader header, Set<String> ignorePkgs) {
		RequireBundleObject[] bundles = header.getRequiredBundles();
		Set<String> result = new HashSet<>((4 / 3) * (bundles.length) + 2);
		ArrayList<IPluginBase> plugins = new ArrayList<>();
		for (int i = 0; i < bundles.length; i++) {
			String id = bundles[i].getId();
			result.add(id);
			IPluginModelBase base = PluginRegistry.findModel(id);
			if (base != null) {
				ExportPackageDescription[] exportedPkgs = findExportedPackages(base.getBundleDescription());
				for (int j = 0; j < exportedPkgs.length; j++)
					ignorePkgs.add(exportedPkgs[j].getName());
				plugins.add(base.getPluginBase());
			}
		}
		return result;
	}

	private Set<String> findManifestPlugins(Set<String> ignorePkgs) {
		BundleSpecification[] bundles = fBase.getBundleDescription().getRequiredBundles();
		Set<String> result = new HashSet<>((4 / 3) * (bundles.length) + 2);
		ArrayList<IPluginBase> plugins = new ArrayList<>();
		for (int i = 0; i < bundles.length; i++) {
			String id = bundles[i].getName();
			result.add(id);
			IPluginModelBase base = PluginRegistry.findModel(id);
			if (base != null) {
				ExportPackageDescription[] exportedPkgs = findExportedPackages(base.getBundleDescription());
				for (int j = 0; j < exportedPkgs.length; j++)
					ignorePkgs.add(exportedPkgs[j].getName());
				plugins.add(base.getPluginBase());
			}
		}
		return result;
	}

	protected final ExportPackageDescription[] findExportedPackages(BundleDescription desc) {
		if (desc != null) {
			IBundle bundle = fBase.getBundleModel().getBundle();
			String value = bundle.getHeader(Constants.BUNDLE_SYMBOLICNAME);
			int index = (value != null) ? value.indexOf(';') : -1;
			String projectBundleId = (index > 0) ? value.substring(0, index) : value;
			List<ExportPackageDescription> result = new LinkedList<>();
			Stack<BaseDescription> stack = new Stack<>();
			stack.add(desc);
			while (!stack.isEmpty()) {
				BundleDescription bdesc = (BundleDescription) stack.pop();
				ExportPackageDescription[] expkgs = bdesc.getExportPackages();
				for (int i = 0; i < expkgs.length; i++)
					if (addPackage(projectBundleId, expkgs[i]))
						result.add(expkgs[i]);

				// Look at re-exported Require-Bundles for any other exported packages
				BundleSpecification[] requiredBundles = bdesc.getRequiredBundles();
				for (int i = 0; i < requiredBundles.length; i++)
					if (requiredBundles[i].isExported()) {
						BaseDescription bd = requiredBundles[i].getSupplier();
						if (bd != null && bd instanceof BundleDescription)
							stack.add(bd);
					}
			}
			return result.toArray(new ExportPackageDescription[result.size()]);
		}
		return new ExportPackageDescription[0];
	}

	private boolean addPackage(String symbolicName, ExportPackageDescription pkg) {
		if (symbolicName == null)
			return true;
		String[] friends = (String[]) pkg.getDirective(ICoreConstants.FRIENDS_DIRECTIVE);
		if (friends != null) {
			for (int i = 0; i < friends.length; i++) {
				if (symbolicName.equals(friends[i]))
					return true;
			}
			return false;
		}
		return true;
	}

	protected final void findImportPackages(IBundle bundle, Set<String> ignorePkgs) {
		IManifestHeader header = bundle.getManifestHeader(Constants.IMPORT_PACKAGE);
		if (header == null || header.getValue() == null)
			return;
		if (header instanceof ImportPackageHeader) {
			ImportPackageObject[] pkgs = ((ImportPackageHeader) header).getPackages();
			for (int i = 0; i < pkgs.length; i++)
				ignorePkgs.add(pkgs[i].getName());
		} else {
			ImportPackageSpecification[] pkgs = fBase.getBundleDescription().getImportPackages();
			for (int i = 0; i < pkgs.length; i++)
				ignorePkgs.add(pkgs[i].getName());
		}
	}

	protected void findSecondaryDependencies(String[] secDeps, Set<String> ignorePkgs, Map<ExportPackageDescription, String> newDeps, IBundle bundle, boolean useRequireBundle, IProgressMonitor monitor) {
		IJavaProject jProject = JavaCore.create(fProject);
		SearchEngine engine = new SearchEngine();
		if (ignorePkgs == null)
			ignorePkgs = new HashSet<>(2);
		SubMonitor subMonitor = SubMonitor.convert(monitor, PDEUIMessages.AddNewDependenciesOperation_searchProject,
				secDeps.length);
		for (int j = 0; j < secDeps.length; j++) {
			try {
				SubMonitor iterationMonitor = subMonitor.newChild(1);
				if (iterationMonitor.isCanceled()) {
					return;
				}
				String pluginId = secDeps[j];
				IPluginModelBase base = PluginRegistry.findModel(secDeps[j]);
				if (base != null) {
					ExportPackageDescription[] exported = findExportedPackages(base.getBundleDescription());
					IJavaSearchScope searchScope = PluginJavaSearchUtil.createSeachScope(jProject);
					iterationMonitor.beginTask(
							NLS.bind(PDEUIMessages.AddNewDependenciesOperation_searchForDependency, pluginId),
							exported.length);
					for (int i = 0; i < exported.length; i++) {
						String pkgName = exported[i].getName();
						if (!ignorePkgs.contains(pkgName)) {
							ReferenceFinder requestor = new ReferenceFinder();
							engine.search(SearchPattern.createPattern(pkgName, IJavaSearchConstants.PACKAGE, IJavaSearchConstants.REFERENCES, SearchPattern.R_EXACT_MATCH), new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()}, searchScope, requestor, null);
							if (requestor.foundMatches()) {
								fNewDependencies = true;
								ignorePkgs.add(pkgName);
								newDeps.put(exported[i], pluginId);
								if (useRequireBundle) {
									// since using require-bundle, rest of packages will be available when bundle is added.
									for (; i < exported.length; i++)
										ignorePkgs.add(exported[i].getName());
								}
							}
						}
						iterationMonitor.worked(1);
					}
				}
			} catch (CoreException e) {
				PDEPlugin.logException(e);
			}
		}
	}

	protected void addProjectPackages(IBundle bundle, Set<String> ignorePkgs) {
		IBuild build = getBuild();
		if (build == null)
			return;
		IBuildEntry binIncludes = build.getEntry(IBuildEntry.BIN_INCLUDES);
		if (binIncludes != null) {
			String value = bundle.getHeader(Constants.BUNDLE_CLASSPATH);
			if (value == null)
				value = "."; //$NON-NLS-1$
			ManifestElement elems[];
			try {
				elems = ManifestElement.parseHeader(Constants.BUNDLE_CLASSPATH, value);
			} catch (BundleException e) {
				return;
			}
			IJavaProject jProject = JavaCore.create(fProject);
			for (int i = 0; i < elems.length; i++) {
				String library = elems[i].getValue();
				// we only want to include packages that will be avialable after exporting (ie. whatever is included in bin.includes)
				if (binIncludes.contains(library)) {
					// if the library is in the bin.includes, see if it is source folder that will be compile.  This way we can search source folders
					IBuildEntry entry = build.getEntry(IBuildEntry.JAR_PREFIX + library);
					if (entry != null) {
						String[] resources = entry.getTokens();
						for (int j = 0; j < resources.length; j++)
							addPackagesFromResource(jProject, fProject.findMember(resources[j]), ignorePkgs);
					} else {
						// if there is no source entry for the library, assume it is a binary jar and try to add it if it exists
						addPackagesFromResource(jProject, fProject.findMember(library), ignorePkgs);
					}
				} else {
					// if it is not found in the bin.includes, see if a parent folder is.  This is common for binary jar.
					StringTokenizer tokenizer = new StringTokenizer(library, "/"); //$NON-NLS-1$
					StringBuffer buffer = new StringBuffer();
					while (tokenizer.hasMoreTokens()) {
						buffer.append(tokenizer.nextToken()).append('/');
						if (binIncludes.contains(buffer.toString()))
							addPackagesFromResource(jProject, fProject.findMember(library), ignorePkgs);
					}
				}
			}
		}
	}

	private void addPackagesFromResource(IJavaProject jProject, IResource res, Set<String> ignorePkgs) {
		if (res == null)
			return;
		try {
			IPackageFragmentRoot root = jProject.getPackageFragmentRoot(res);
			IJavaElement[] children = root.getChildren();
			for (int i = 0; i < children.length; i++) {
				String pkgName = children[i].getElementName();
				if (children[i] instanceof IParent)
					if (pkgName.length() > 0 && ((IParent) children[i]).hasChildren())
						ignorePkgs.add(children[i].getElementName());
			}
		} catch (JavaModelException e) {
		}
	}

	protected void handleNewDependencies(final Map<ExportPackageDescription, String> additionalDeps, final boolean useRequireBundle, IProgressMonitor monitor) {
		if (!additionalDeps.isEmpty())
			addDependencies(additionalDeps, useRequireBundle);
	}

	protected void addDependencies(final Map<ExportPackageDescription, String> depsToAdd, boolean useRequireBundle) {
		if (useRequireBundle) {
			Collection<String> plugins = depsToAdd.values();
			minimizeBundles(plugins);
			IBuild build = getBuild();
			IPluginBase pbase = fBase.getPluginBase();
			if (pbase == null) {
				addRequireBundles(plugins, fBase.getBundleModel().getBundle(), build.getEntry(IBuildEntry.SECONDARY_DEPENDENCIES));
			} else
				addRequireBundles(plugins, pbase, build.getEntry(IBuildEntry.SECONDARY_DEPENDENCIES));
			try {
				build.write("", new PrintWriter(new FileOutputStream(PDEProject.getBuildProperties(fProject).getFullPath().toFile()))); //$NON-NLS-1$
			} catch (FileNotFoundException e) {
			}
		} else {
			Collection<ExportPackageDescription> pkgs = depsToAdd.keySet();
			addImportPackages(pkgs, fBase.getBundleModel().getBundle());
		}
	}

	protected final void addImportPackages(final Collection<ExportPackageDescription> depsToAdd, final IBundle bundle) {
		Iterator<ExportPackageDescription> it = depsToAdd.iterator();
		IManifestHeader mheader = bundle.getManifestHeader(Constants.IMPORT_PACKAGE);
		// always create header.  When available, ImportPackageHeader will help with formatting (see bug 149976)
		if (mheader == null) {
			bundle.setHeader(Constants.IMPORT_PACKAGE, new String());
			mheader = bundle.getManifestHeader(Constants.IMPORT_PACKAGE);
		}
		if (mheader instanceof ImportPackageHeader) {
			ImportPackageHeader header = (ImportPackageHeader) mheader;
			String versionAttr = (BundlePluginBase.getBundleManifestVersion(bundle) < 2) ? ICoreConstants.PACKAGE_SPECIFICATION_VERSION : Constants.VERSION_ATTRIBUTE;
			while (it.hasNext()) {
				ImportPackageObject obj = new ImportPackageObject(header, it.next(), versionAttr);
				header.addPackage(obj);
			}
		} else {
			String currentValue = (mheader != null) ? mheader.getValue() : null;
			StringBuffer buffer = (currentValue == null) ? new StringBuffer() : new StringBuffer(currentValue).append(", "); //$NON-NLS-1$
			while (it.hasNext()) {
				ExportPackageDescription desc = it.next();
				String value = (desc.getVersion().equals(Version.emptyVersion)) ? desc.getName() : desc.getName() + "; version=\"" + desc.getVersion() + "\""; //$NON-NLS-1$ //$NON-NLS-2$
				// use same separator as used when writing out Manifest
				buffer.append(value).append(ManifestUtils.MANIFEST_LIST_SEPARATOR);
			}
			if (buffer.length() > 0)
				buffer.setLength(buffer.length() - ManifestUtils.MANIFEST_LIST_SEPARATOR.length());
			bundle.setHeader(Constants.IMPORT_PACKAGE, buffer.toString());
		}
	}

	protected final void addRequireBundles(final Collection<String> depsToAdd, final IBundle bundle, IBuildEntry entry) {
		if (bundle == null)
			return;
		HashSet<String> added = new HashSet<>();
		Iterator<String> it = depsToAdd.iterator();
		IManifestHeader mheader = bundle.getManifestHeader(Constants.REQUIRE_BUNDLE);
		if (mheader instanceof RequireBundleHeader) {
			RequireBundleHeader header = (RequireBundleHeader) mheader;
			while (it.hasNext()) {
				String pluginId = it.next();
				if (!added.contains(pluginId))
					try {
						header.addBundle(pluginId);
						added.add(pluginId);
						entry.removeToken(pluginId);
					} catch (CoreException e) {
					}
			}
		} else {
			String currentValue = (mheader != null) ? mheader.getValue() : null;
			StringBuffer buffer = (currentValue == null) ? new StringBuffer() : new StringBuffer(currentValue).append(", "); //$NON-NLS-1$
			while (it.hasNext()) {
				String pluginId = it.next();
				if (!added.contains(pluginId))
					try {
						buffer.append(pluginId).append(ManifestUtils.MANIFEST_LIST_SEPARATOR);
						added.add(pluginId);
						entry.removeToken(pluginId);
					} catch (CoreException e) {
					}
			}
			if (buffer.length() > 0)
				buffer.setLength(buffer.length() - ManifestUtils.MANIFEST_LIST_SEPARATOR.length());
			bundle.setHeader(Constants.REQUIRE_BUNDLE, buffer.toString());
		}
	}

	protected final void addRequireBundles(final Collection<String> depsToAdd, final IPluginBase base, IBuildEntry entry) {
		HashSet<String> added = new HashSet<>();
		Iterator<String> it = depsToAdd.iterator();
		// must call getImports to initialize IPluginBase.  Otherwise the .add(plugin) will not trigger a modification event.
		base.getImports();
		while (it.hasNext()) {
			String pluginId = it.next();
			if (!added.contains(pluginId))
				try {
					PluginImport plugin = new PluginImport();
					ManifestElement element = ManifestElement.parseHeader(Constants.REQUIRE_BUNDLE, pluginId)[0];
					plugin.load(element, 1);
					plugin.setModel(base.getModel());
					base.add(plugin);
					added.add(pluginId);
					if (entry != null && entry.contains(pluginId))
						entry.removeToken(pluginId);
				} catch (BundleException e) {
				} catch (CoreException e) {
				}
		}
	}

	protected final void minimizeBundles(Collection<String> pluginIds) {
		Stack<String> stack = new Stack<>();
		Iterator<String> it = pluginIds.iterator();
		while (it.hasNext())
			stack.push(it.next().toString());

		while (!stack.isEmpty()) {
			IPluginModelBase base = PluginRegistry.findModel(stack.pop().toString());
			if (base == null)
				continue;
			IPluginImport[] imports = base.getPluginBase().getImports();

			for (int j = 0; j < imports.length; j++)
				if (imports[j].isReexported()) {
					String reExportedId = imports[j].getId();
					pluginIds.remove(imports[j].getId());
					stack.push(reExportedId);
				}
		}
	}
}
