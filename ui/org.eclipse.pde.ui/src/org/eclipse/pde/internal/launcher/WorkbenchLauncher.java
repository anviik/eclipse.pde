package org.eclipse.pde.internal.launcher;
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import org.eclipse.ui.plugin.*;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.operation.*;
import org.eclipse.pde.internal.base.model.plugin.*;
import org.eclipse.jface.preference.*;
import java.io.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.eval.*;
import java.util.*;
import java.io.File;
import org.eclipse.pde.internal.preferences.*;
import org.eclipse.core.resources.*;
import org.eclipse.jdt.core.*;
import org.eclipse.debug.core.*;
import org.eclipse.debug.core.model.*;
import org.eclipse.pde.internal.*;
import org.eclipse.jdt.launching.*;
import java.lang.reflect.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.jdt.launching.sourcelookup.JavaSourceLocator;

public class WorkbenchLauncher
	implements ILauncherDelegate, IExecutableExtension {
	private ISourceLocator sourceLocator;
	private boolean tracingEnabled;
	protected boolean commandLineShown = false;
	private IProject projectToLaunch;
	private IType mainType;
	private final static int ALL_MODELS = 1;
	private final static int NO_MODELS = 2;
	private final static String NO_LAUNCHER_TITLE =
		"WorkbenchLauncher.noLauncher.title";
	private final static String NO_LAUNCHER_MESSAGE =
		"WorkbenchLauncher.noLauncher.message";
	private final static String KEY_STARTING = "WorkbenchLauncher.starting";
	private final static int SELECTED_MODELS = 3;
	protected String vmArgs;
	protected String platformArgs;
	protected String platformLocation;

	class MainTypeAdaptable implements IAdaptable, IWorkbenchAdapter {
		String typeName;
		IProject project;
		public MainTypeAdaptable(String name, IProject project) {
			int loc = name.lastIndexOf('.');
			this.typeName = name.substring(loc + 1);
			this.project = project;
		}
		public IProject getProject() {
			return project;
		}
		public Object getAdapter(Class key) {
			if (key.equals(IWorkbenchAdapter.class)) {
				return this;
			}
			return null;
		}
		public Object[] getChildren(Object parent) {
			return new Object[0];
		}
		public ImageDescriptor getImageDescriptor(Object object) {
			return null;
		}
		public String getLabel(Object object) {
			return typeName;
		}
		public Object getParent(Object object) {
			return null;
		}
		public boolean equals(Object object) {
			if (object instanceof MainTypeAdaptable) {
				MainTypeAdaptable another = (MainTypeAdaptable) object;
				if (another.typeName.equals(typeName) && another.project.equals(project))
					return true;
			}
			return false;
		}
	}

	public WorkbenchLauncher() {
		super();
	}
	public static void addExternalModels(PrintWriter writer, String itpDir) {
		Vector externalModels = new Vector();
		int externalMode = getExternalMode(externalModels);
		if (externalMode == ALL_MODELS) {
			String line = "platformPath = file:" + itpDir + File.separator + "plugins/";
			line = fixEscapeChars(line);
			writer.println(line);
		} else if (externalMode == SELECTED_MODELS) {
			for (int i = 0; i < externalModels.size(); i++) {
				IPluginModel model = (IPluginModel) externalModels.elementAt(i);
				addOneModel(writer, model);
			}
		}
	}
	
	private static void addOneModel(PrintWriter writer, IPluginModelBase model) {
		String location = model.getInstallLocation();
		String fileName = model instanceof IFragmentModel ? "fragment.xml":"plugin.xml";
		String path = "file:" + location + File.separator + fileName;
		String line = model.getPluginBase().getId() + " = " + path;
		line = fixEscapeChars(line);
		writer.println(line);
	}

	public static void addWorkspaceModels(PrintWriter writer) {
		IWorkspace workspace = PDEPlugin.getWorkspace();
		IProject[] projects = workspace.getRoot().getProjects();
		for (int i = 0; i < projects.length; i++) {
			IProject project = projects[i];
			try {
				if (project.hasNature(JavaCore.NATURE_ID) == false)
					continue;
				if (project.hasNature(PDEPlugin.COMPONENT_NATURE))
					continue;
			} catch (CoreException e) {
				continue;
			}
			IFile file = getPluginFile(project);
			if (file == null)
				file = getFragmentFile(project);
			if (file != null) {
				String key = project.getName();
				String value = "file:" + file.getLocation().toOSString();
				value = fixEscapeChars(value);
				writer.println(key + " = " + value);
			}
		}
	}

	private static IFile getPluginFile(IProject project) {
		IPath path = project.getFullPath().append("plugin.xml");
		IFile file = project.getWorkspace().getRoot().getFile(path);
		if (file.exists())
			return file;
		return null;
	}

	private static IFile getFragmentFile(IProject project) {
		IPath path = project.getFullPath().append("fragment.xml");
		IFile file = project.getWorkspace().getRoot().getFile(path);
		if (file.exists())
			return file;
		return null;
	}

	private String[] buildClassPath(String eclipseDir) {
		String startupPath = eclipseDir + File.separator + "startup.jar";
		String newStartupPath =
			eclipseDir + File.separator + "bin" + File.separator + "startup.jar";
		return new String[] { startupPath, newStartupPath };
	}

	private String createPluginPath(String eclipseDir) {
		IPath stateLocation = PDEPlugin.getDefault().getStateLocation();

		File file = stateLocation.append("pde_plugin_path.properties").toFile();

		String fileName = file.getAbsolutePath();

		try {
			OutputStream stream = new FileOutputStream(file);
			PrintWriter writer = new PrintWriter(stream);
			String projectPath = Platform.getLocation().toOSString();
			addExternalModels(writer, eclipseDir);
			addWorkspaceModels(writer);
			writer.flush();
			writer.close();
		} catch (IOException e) {
			return null;
		}
		if (SWT.getPlatform().equals("motif")) {
			return "-plugins " + fileName;
		}
		String option = "-plugins \"" + fileName + "\"";
		return option;
	}

	protected boolean doLaunch(
		final IJavaProject p,
		final String mode,
		final String mainType,
		ExecutionArguments args,
		String[] classPath,
		final ILauncher launcherProxy) throws CoreException {
		try {
			final IVMRunner launcher = getJavaLauncher(p, mode);
			if (launcher == null) {
				showNoLauncherDialog();
				return false;
			}

			String[] vmArgs = null;
			String[] programArgs = null;
			if (args != null) {
				vmArgs = args.getVMArgumentsArray();
				programArgs = args.getProgramArgumentsArray();
			}
			final VMRunnerConfiguration config =
				new VMRunnerConfiguration(mainType, classPath);
			config.setVMArguments(vmArgs);
			config.setProgramArguments(programArgs);

			IRunnableWithProgress r = new IRunnableWithProgress() {
				public void run(IProgressMonitor pm) throws InvocationTargetException {
					if (!p.getProject().getWorkspace().isAutoBuilding()) {
						try {
							p.getProject().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, pm);
						} catch (CoreException e) {
							throw new InvocationTargetException(e);
						}
					}
					pm.beginTask(
						PDEPlugin.getResourceString(KEY_STARTING),
						IProgressMonitor.UNKNOWN);
					VMRunnerResult result = null;
					try {
						result = launcher.run(config);
					} catch (CoreException e) {
						throw new InvocationTargetException(e);
					}
					if (result != null) {
						Launch newLaunch =
							new Launch(
								launcherProxy,
								mode,
								new MainTypeAdaptable(mainType, projectToLaunch),
								getSourceLocator(),
								result.getProcesses(),
								result.getDebugTarget());
						registerLaunch(newLaunch);
					}
				}
			};

			try {
				new ProgressMonitorDialog(PDEPlugin.getActiveWorkbenchShell()).run(
					true,
					false,
					r);
			} catch (InterruptedException e) {
				PDEPlugin.logException(e);
				return false;
			} catch (InvocationTargetException e) {
				PDEPlugin.logException(e);
				return false;
			}
			return true;
		} catch (CoreException e) {
			PDEPlugin.logException(e);
		}
		return false;
	}

	private IProject getCorrespondingProject(Object runnable)
		throws CoreException {
		if (runnable instanceof IFile) {
			IFile file = (IFile) runnable;
			return file.getProject();
		}
		if (runnable instanceof IJavaProject) {
			return ((IJavaProject) runnable).getProject();
		}
		if (runnable instanceof IProject) {
			return (IProject) runnable;
		}
		if (runnable instanceof MainTypeAdaptable) {
			return ((MainTypeAdaptable) runnable).getProject();
		}
		if (runnable instanceof IJavaElement) {
			IJavaElement je = (IJavaElement) runnable;
			return je.getJavaProject().getProject();
		}
		return null;
	}

	public boolean findProjectToLaunch(Object[] objects) {
		Object runnable = objects[0];

		projectToLaunch = null;
		try {
			projectToLaunch = getCorrespondingProject(runnable);
			if (projectToLaunch == null
				|| projectToLaunch.hasNature(PDEPlugin.PLUGIN_NATURE) == false) {
				return false;
			}
		} catch (CoreException e) {
			PDEPlugin.logException(e);
			return false;
		}
		return true;
	}
	public static String fixEscapeChars(String input) {
		if (input.indexOf('\\') == -1)
			return input;
		StringBuffer buff = new StringBuffer();

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '\\') {
				if (i == input.length() - 1 || input.charAt(i + 1) != '\\') {
					buff.append(c);
				}
			}
			buff.append(c);
		}
		return buff.toString();
	}
	private static int getExternalMode(Vector result) {
		ExternalModelManager registry =
			PDEPlugin.getDefault().getExternalModelManager();
		IPluginModel[] models = registry.getModels();
		for (int i = 0; i < models.length; i++) {
			IPluginModel model = models[i];
			if (model.isEnabled()) {
				result.add(model);
			}
		}
		if (result.size() == 0)
			return NO_MODELS;
		if (result.size() == models.length)
			return ALL_MODELS;
		return SELECTED_MODELS;
	}
	protected IVMRunner getJavaLauncher(IJavaProject p, String mode)
		throws CoreException {
		if (p != null) {
			IVMInstall vm = JavaRuntime.getVMInstall(p);
			if (vm == null)
				vm = JavaRuntime.getDefaultVMInstall();
			if (vm != null)
				return vm.getVMRunner(mode);
		}
		return null;
	}
	public ISourceLocator getSourceLocator() {
		return sourceLocator;
	}
	protected void initializeSettings() {
		IPreferenceStore pstore = PDEPlugin.getDefault().getPreferenceStore();
		vmArgs = "-verify";
		String userVMArgs = pstore.getString(PDEBasePreferencePage.PROP_VM_ARGS);
		if (userVMArgs != null)
			vmArgs += " " + userVMArgs.trim();
		platformLocation =
			pstore.getString(PDEBasePreferencePage.PROP_PLATFORM_LOCATION);
		if (platformLocation != null)
			platformLocation = platformLocation.trim();
		platformArgs = pstore.getString(PDEBasePreferencePage.PROP_PLATFORM_ARGS);
		if (tracingEnabled) {
			TracingOptionsManager mng = PDEPlugin.getDefault().getTracingOptionsManager();
			mng.ensureTracingFileExists();
			String optionsFileName = mng.getTracingFileName();
			String tracingArg;
			if (SWT.getPlatform().equals("motif"))
				tracingArg = "-debug file:" + optionsFileName;
			else
				tracingArg = "-debug \"file:" + optionsFileName + "\"";
			platformArgs += tracingArg;
		}
	}
	public boolean launch(Object[] elements, String mode, ILauncher launcher) {
		if (!findProjectToLaunch(elements))
			return false;
		initializeSettings();
		if (promptForSettings() == false) {
			return false;
		}

		IJavaProject javaProject = JavaCore.create(projectToLaunch);
		String mainType = "org.eclipse.core.launcher.UIMain";
		try {
			sourceLocator = new JavaSourceLocator(JavaSourceLocator.getDefaultSourceLocations(javaProject));

			IPreferenceStore pstore = PDEPlugin.getDefault().getPreferenceStore();
			String targetLocation =
				pstore.getString(PDEBasePreferencePage.PROP_PLATFORM_PATH);
			if (targetLocation != null)
				targetLocation = targetLocation.trim();
			String eclipseDir = targetLocation; // + File.separator + "eclipse";

			String programArgs = "-dev bin -application org.eclipse.ui.workbench";

			if (platformLocation != null && platformLocation.length() > 0) {
				String dataOption;
				if (SWT.getPlatform().equals("motif"))
					dataOption = " -data " + platformLocation;
				else
					dataOption = " -data \"" + platformLocation + "\"";
				programArgs += dataOption;
			}
			String pluginPath = createPluginPath(eclipseDir);
			if (pluginPath == null)
				return false;
			programArgs += " " + pluginPath;
			if (platformArgs != null)
				programArgs += " " + platformArgs;

			String[] classPath = buildClassPath(eclipseDir);

			ExecutionArguments args = new ExecutionArguments(vmArgs, programArgs);

			return doLaunch(javaProject, mode, mainType, args, classPath, launcher);

		} catch (Exception e) {
			PDEPlugin.logException(e);
		}
		return false;
	}
	protected boolean promptForSettings() {
		return true;
	}
	private void registerLaunch(final ILaunch launch) {
		Display display = Display.getCurrent();
		if (display == null)
			display = Display.getDefault();
		display.syncExec(new Runnable() {
			public void run() {
				DebugPlugin.getDefault().getLaunchManager().addLaunch(launch);
			}
		});
	}
	public void setInitializationData(
		IConfigurationElement config,
		String propertyName,
		Object data)
		throws CoreException {
		String mode = data != null ? data.toString() : "";
		if (mode.equals("tracingEnabled"))
			tracingEnabled = true;
	}
	private void showNoLauncherDialog() {
		MessageDialog.openError(
			PDEPlugin.getActiveWorkbenchShell(),
			PDEPlugin.getResourceString(NO_LAUNCHER_TITLE),
			PDEPlugin.getResourceString(NO_LAUNCHER_MESSAGE));
	}

	public String getLaunchMemento(Object object) {
		try {
			IProject project = getCorrespondingProject(object);
			if (project == null)
				return null;
			return project.getName();
		} catch (CoreException e) {
			PDEPlugin.logException(e);
			return null;
		}
	}

	public Object getLaunchObject(String memento) {
		if (memento == null)
			return null;
		IProject project = PDEPlugin.getWorkspace().getRoot().getProject(memento);
		if (project.exists())
			return project;
		return null;
	}

}