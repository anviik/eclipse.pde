/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.internal.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.api.tools.internal.IApiCoreConstants;
import org.eclipse.pde.api.tools.internal.comparator.Delta;
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFactory;
import org.eclipse.pde.api.tools.internal.provisional.ApiPlugin;
import org.eclipse.pde.api.tools.internal.provisional.Factory;
import org.eclipse.pde.api.tools.internal.provisional.IApiComponent;
import org.eclipse.pde.api.tools.internal.provisional.IApiMarkerConstants;
import org.eclipse.pde.api.tools.internal.provisional.IApiProfile;
import org.eclipse.pde.api.tools.internal.provisional.IClassFile;
import org.eclipse.pde.api.tools.internal.provisional.RestrictionModifiers;
import org.eclipse.pde.api.tools.internal.provisional.VisibilityModifiers;
import org.eclipse.pde.api.tools.internal.provisional.builder.IApiAnalyzer;
import org.eclipse.pde.api.tools.internal.provisional.comparator.ApiComparator;
import org.eclipse.pde.api.tools.internal.provisional.comparator.DeltaProcessor;
import org.eclipse.pde.api.tools.internal.provisional.comparator.IDelta;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IElementDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IReferenceTypeDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblemTypes;
import org.eclipse.pde.api.tools.internal.provisional.search.IApiSearchScope;
import org.eclipse.pde.api.tools.internal.util.SinceTagVersion;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;

import com.ibm.icu.text.MessageFormat;

/**
 * Base implementation of the analyzer used in the {@link ApiAnalysisBuilder}
 * 
 * @since 1.0.0
 */
public class BaseApiAnalyzer implements IApiAnalyzer {

	/**
	 * Constant used for controlling tracing in the API tool builder
	 */
	private static boolean DEBUG = Util.DEBUG;
	
	/**
	 * The backing list of problems found so far
	 */
	private HashSet fProblems = new HashSet(25);
	
	/**
	 * List of pending deltas for which the @since tags should be checked
	 */
	private List fPendingDeltaInfos = new ArrayList(3);
	
	/**
	 * the project context, or <code>null</code> if we are headless
	 */
	private IProject fCurrentProject = null;
	
	/**
	 * The current build state to use
	 */
	private BuildState fBuildState = null;
	
	/**
	 * Method used for initializing tracing in the API tool builder
	 */
	public static void setDebug(boolean debugValue) {
		DEBUG = debugValue || Util.DEBUG;
	}
	
	/**
	 * Constructor
	 * @param project the project being built, or <code>null</code> during
	 * a headless build
	 */
	public BaseApiAnalyzer(IProject project, BuildState state) {
		fCurrentProject = project;
		fBuildState = state;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.builder.IApiAnalyzer#analyzeComponent(org.eclipse.pde.api.tools.internal.provisional.IApiProfile, org.eclipse.pde.api.tools.internal.provisional.IApiComponent, java.lang.String[], org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void analyzeComponent(final IApiProfile profile, final IApiComponent component, final String[] typenames, IProgressMonitor monitor) {
		IProgressMonitor localMonitor = SubMonitor.convert(monitor, BuilderMessages.BaseApiAnalyzer_analzing_api, 3 + (typenames == null ? 0 : typenames.length));
		IApiComponent reference = profile.getApiComponent(component.getId());
		if(fBuildState == null) {
			fBuildState = getBuildState();
		}
		//compatibility checks
		if(fCurrentProject != null) {
			if(reference != null) {
				IJavaProject jproject = JavaCore.create(fCurrentProject);
				localMonitor.subTask(NLS.bind(BuilderMessages.BaseApiAnalyzer_comparing_api_profiles, fCurrentProject.getName()));
				if(typenames != null) {
					for(int i = 0; i < typenames.length; i++) {
						compareProfiles(jproject, typenames[i], reference, component);
						updateMonitor(localMonitor);
					}
				}
				else {
					compareProfiles(jproject, reference, component);
					updateMonitor(localMonitor);
				}
			}
		}
		//usage checks
		checkApiUsage(component, getSearchScope(component, typenames), localMonitor);
		updateMonitor(localMonitor);
		//version checks
		checkApiComponentVersion(reference, component);
		updateMonitor(localMonitor);
		//check default baseline
		checkDefaultBaselineSet();
		updateMonitor(localMonitor);
	}

	/**
	 * @return the build state to use.
	 */
	private BuildState getBuildState() {
		if(fCurrentProject == null) {
			return new BuildState();
		}
		try {
			BuildState state = ApiAnalysisBuilder.getLastBuiltState(fCurrentProject);
			if(state != null) {
				return state;
			}
		} 
		catch (CoreException e) {}
		return new BuildState();
	}
	
	/**
	 * Returns an {@link IApiSearchScope} given the component and type names context
	 * @param component
	 * @param types
	 * @return a new {@link IApiSearchScope} for the component and type names context
	 */
	private IApiSearchScope getSearchScope(final IApiComponent component, final String[] typenames) {
		if(typenames == null) {
			return Factory.newScope(new IApiComponent[]{component});
		}
		else {
			return Factory.newTypeScope(component, getScopedElements(typenames));
		}
	}
	
	/**
	 * Returns a listing of {@link IReferenceTypeDescriptor}s given the listing of type names
	 * @param typenames
	 * @return
	 */
	private IReferenceTypeDescriptor[] getScopedElements(final String[] typenames) {
		ArrayList types = new ArrayList(typenames.length);
		for(int i = 0; i < typenames.length; i++) {
			types.add(Util.getType(typenames[i]));
		}
		return (IReferenceTypeDescriptor[]) types.toArray(new IReferenceTypeDescriptor[types.size()]);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.builder.IApiAnalyzer#getProblems()
	 */
	public IApiProblem[] getProblems() {
		if(fProblems == null) {
			return new IApiProblem[0];
		}
		return (IApiProblem[]) fProblems.toArray(new IApiProblem[fProblems.size()]);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.pde.api.tools.internal.provisional.builder.IApiAnalyzer#dispose()
	 */
	public void dispose() {
		if(fProblems != null) {
			fProblems.clear();
			fProblems = null;
		}
		if(fPendingDeltaInfos != null) {
			fPendingDeltaInfos.clear();
			fPendingDeltaInfos = null;
		}
		fCurrentProject = null;
		if(fBuildState != null) {
			fBuildState = null;
		}
	}
	
	/**
	 * @return if the API usage scan should be ignored
	 */
	private boolean ignoreApiUsageScan() {
		boolean ignore = true;
		ApiPlugin plugin = ApiPlugin.getDefault();
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.ILLEGAL_EXTEND, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.ILLEGAL_IMPLEMENT, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.ILLEGAL_INSTANTIATE, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.ILLEGAL_REFERENCE, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.LEAK_EXTEND, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.LEAK_FIELD_DECL, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.LEAK_IMPLEMENT, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.LEAK_METHOD_PARAM, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		ignore &= plugin.getSeverityLevel(IApiProblemTypes.LEAK_METHOD_RETURN_TYPE, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		return ignore;
	}
	
	/**
	 * @return if the default API baseline check should be ignored or not
	 */
	private boolean ignoreDefaultBaselineCheck() {
		if(fCurrentProject == null) {
			return true;
		}
		return ApiPlugin.getDefault().getSeverityLevel(IApiProblemTypes.MISSING_DEFAULT_API_BASELINE, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
	}
	
	/**
	 * Whether to ignore since tag checks. If <code>null</code> is passed in we are asking if all since tag checks should be ignored,
	 * if a pref is specified we only want to know if that kind should be ignored
	 * @param pref
	 * @return
	 */
	private boolean ignoreSinceTagCheck(String pref) {
		if(pref == null) {
			boolean all = ApiPlugin.getDefault().getSeverityLevel(IApiProblemTypes.MALFORMED_SINCE_TAG, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
			all &= ApiPlugin.getDefault().getSeverityLevel(IApiProblemTypes.INVALID_SINCE_TAG_VERSION, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
			all &= ApiPlugin.getDefault().getSeverityLevel(IApiProblemTypes.MISSING_SINCE_TAG, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
			return all;
		}
		else {
			return ApiPlugin.getDefault().getSeverityLevel(pref, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
		}
	}
	
	/**
	 * @return if the component version checks should be ignored or not
	 */
	private boolean ignoreComponentVersionCheck() {
		if(fCurrentProject == null) {
			return true;
		}
		return ApiPlugin.getDefault().getSeverityLevel(IApiProblemTypes.INCOMPATIBLE_API_COMPONENT_VERSION, fCurrentProject) == ApiPlugin.SEVERITY_IGNORE;
	}
	
	/**
	 * Checks for illegal API usage in the specified component, creating problem
	 * markers as required.
	 * 
	 * @param profile profile being analyzed
	 * @param component component being built
	 * @param scope scope being built
	 * @param monitor progress monitor
	 */
	private void checkApiUsage(final IApiComponent component, final IApiSearchScope scope, IProgressMonitor monitor) {
		if(ignoreApiUsageScan()) {
			if(DEBUG) {
				System.out.println("Ignoring API usage scan"); //$NON-NLS-1$
			}
			return;
		}
		String message = fCurrentProject == null ? BuilderMessages.BaseApiAnalyzer_checking_api_usage : MessageFormat.format(BuilderMessages.checking_api_usage, new String[] {fCurrentProject.getName()});
		IProgressMonitor localMonitor = SubMonitor.convert(monitor, message, 2);
		ApiUseAnalyzer analyzer = new ApiUseAnalyzer();
		try {
			long start = System.currentTimeMillis();
			IApiProblem[] illegal = analyzer.findIllegalApiUse(component, scope, monitor);
			updateMonitor(localMonitor);
			long end = System.currentTimeMillis();
			if (DEBUG) {
				System.out.println("API usage scan: " + (end- start) + " ms\t" + illegal.length + " problems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}		
			if (illegal.length > 0) {
				for (int i = 0; i < illegal.length; i++) {
					fProblems.add(illegal[i]);
				}
			}
			updateMonitor(localMonitor);
		} catch (CoreException e) {
			ApiPlugin.log(e.getStatus());
		}
	}
	
	/**
	 * Compares the given type between the two API components
	 * @param typeName the type to check in each component
	 * @param reference 
	 * @param component
	 */
	private void compareProfiles(final IJavaProject jproject, final String typeName, final IApiComponent reference, final IApiComponent component) {
		if (DEBUG) {
			System.out.println("comparing profiles ["+reference.getId()+"] and ["+component.getId()+"] for type ["+typeName+"]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		IClassFile classFile = null;
		try {
			classFile = component.findClassFile(typeName);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
		if (classFile == null) {
			if (DEBUG) {
				System.err.println("Could not retrieve class file for " + typeName + " in " + component.getId()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return;
		}
		fBuildState.cleanup(typeName);
		IDelta delta = null;
		long time = System.currentTimeMillis();
		try {
			delta = ApiComparator.compare(classFile, reference, component, reference.getProfile(), component.getProfile(), VisibilityModifiers.API);
		} catch(Exception e) {
			ApiPlugin.log(e);
		} finally {
			if (DEBUG) {
				System.out.println("Time spent for " + typeName + " : " + (System.currentTimeMillis() - time) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			fPendingDeltaInfos.clear();
		}
		if (delta == null) {
			return;
		}
		if (delta != ApiComparator.NO_DELTA) {
			List allDeltas = Util.collectAllDeltas(delta);
			for (Iterator iterator = allDeltas.iterator(); iterator.hasNext();) {
				processDelta(jproject, (IDelta) iterator.next(), reference, component);
			}
			if (!fPendingDeltaInfos.isEmpty()) {
				for (Iterator iterator = fPendingDeltaInfos.iterator(); iterator.hasNext();) {
					checkSinceTags(jproject, (Delta) iterator.next(), component);
				}
			}
		}
	}
	/**
	 * Compares the two given profiles and generates an {@link IDelta}
	 * 
	 * @param jproject
	 * @param reference
	 * @param component
	 */
	private void compareProfiles(final IJavaProject jproject, final IApiComponent reference, final IApiComponent component) {
		long time = System.currentTimeMillis();
		IDelta delta = null;
		if (reference == null) {
			delta = new Delta(null, IDelta.API_PROFILE_ELEMENT_TYPE, IDelta.ADDED, IDelta.API_COMPONENT, null, component.getId(), component.getId());
		} else {
			try {
				delta = ApiComparator.compare(reference, component, VisibilityModifiers.API);
			} catch(Exception e) {
				ApiPlugin.log(e);
			} finally {
				if (DEBUG) {
					System.out.println("Time spent for " + component.getId() + " : " + (System.currentTimeMillis() - time) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				fPendingDeltaInfos.clear();
			}
		}
		if (delta == null) {
			return;
		}
		if (delta != ApiComparator.NO_DELTA) {
			List allDeltas = Util.collectAllDeltas(delta);
			if (allDeltas.size() != 0) {
				for (Iterator iterator = allDeltas.iterator(); iterator.hasNext();) {
					processDelta(jproject, (IDelta) iterator.next(), reference, component);
				}
				if (!fPendingDeltaInfos.isEmpty()) {
					for (Iterator iterator = fPendingDeltaInfos.iterator(); iterator.hasNext();) {
						checkSinceTags(jproject, (Delta) iterator.next(), component);
					}
				}
			}
		}
	}
	
	/**
	 * Processes delta to determine if it needs an @since tag. If it does and one
	 * is not present or the version of the tag is incorrect, a marker is created
	 * @param jproject
	 * @param delta
	 * @param component
	 */
	private void checkSinceTags(final IJavaProject jproject, final Delta delta, final IApiComponent component) {
		if(ignoreSinceTagCheck(null)) {
			return;
		}
		IMember member = Util.getIMember(delta, jproject);
		if (member == null) {
			return;
		}
		ICompilationUnit cunit = member.getCompilationUnit();
		if (cunit == null) {
			return;
		}
		try {
			if (! cunit.isConsistent()) {
				cunit.makeConsistent(null);
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		IApiProblem problem = null;
		ASTParser parser = ASTParser.newParser(AST.JLS3);
		ISourceRange nameRange = null;
		try {
			nameRange = member.getNameRange();
		} catch (JavaModelException e) {
			ApiPlugin.log(e);
			return;
		}
		parser.setSource(cunit);
		if (nameRange == null) {
			return;
		}
		try {
			int offset = nameRange.getOffset();
			parser.setFocalPosition(offset);
			parser.setResolveBindings(false);
			Map options = jproject.getOptions(true);
			options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
			parser.setCompilerOptions(options);
			final CompilationUnit unit = (CompilationUnit) parser.createAST(new NullProgressMonitor());
			SinceTagChecker visitor = new SinceTagChecker(offset);
			unit.accept(visitor);
			String componentVersionString = component.getVersion();
			try {
				if (visitor.hasNoComment() || visitor.isMissing()) {
					if(ignoreSinceTagCheck(IApiProblemTypes.MISSING_SINCE_TAG)) {
						if(DEBUG) {
							System.out.println("Ignoring missing since tag problem"); //$NON-NLS-1$
						}
						return;
					}
					StringBuffer buffer = new StringBuffer();
					Version componentVersion = new Version(componentVersionString);
					buffer.append(componentVersion.getMajor()).append('.').append(componentVersion.getMinor());
					problem = createSinceTagProblem(IApiProblem.SINCE_TAG_MISSING, null, delta, member, String.valueOf(buffer));
				} else if (visitor.hasJavadocComment()) {
					// we don't want to flag block comment
					String sinceVersion = visitor.getSinceVersion();
					if (sinceVersion != null) {
						SinceTagVersion tagVersion = new SinceTagVersion(sinceVersion);
						if (Util.getFragmentNumber(sinceVersion) > 2 || tagVersion.getVersion() == null) {
							if(ignoreSinceTagCheck(IApiProblemTypes.MALFORMED_SINCE_TAG)) {
								if(DEBUG) {
									System.out.println("Ignoring malformed since tag problem"); //$NON-NLS-1$
								}
								return;
							}
							StringBuffer buffer = new StringBuffer();
							if (tagVersion.pluginName() != null) {
								buffer.append(tagVersion.pluginName()).append(' ');
							}
							Version componentVersion = new Version(componentVersionString);
							buffer.append(componentVersion.getMajor()).append('.').append(componentVersion.getMinor());
							problem = createSinceTagProblem(IApiProblem.SINCE_TAG_MALFORMED, new String[] {sinceVersion}, delta, member, String.valueOf(buffer));
						} else {
							if(ignoreSinceTagCheck(IApiProblemTypes.INVALID_SINCE_TAG_VERSION)) {
								if(DEBUG) {
									System.out.println("Ignoring invalid tag version problem"); //$NON-NLS-1$
								}
								return;
							}
							StringBuffer accurateVersionBuffer = new StringBuffer();
							Version componentVersion = new Version(componentVersionString);
							accurateVersionBuffer.append(componentVersion.getMajor()).append('.').append(componentVersion.getMinor());
							String accurateVersion = String.valueOf(accurateVersionBuffer);
							if (Util.isDifferentVersion(sinceVersion, accurateVersion)) {
								// report invalid version number
								StringBuffer buffer = new StringBuffer();
								if (tagVersion.pluginName() != null) {
									buffer.append(tagVersion.pluginName()).append(' ');
								}
								Version version = new Version(accurateVersion);
								buffer.append(version.getMajor()).append('.').append(version.getMinor());
								String accurateSinceTagValue = String.valueOf(buffer);
								problem = createSinceTagProblem(IApiProblem.SINCE_TAG_INVALID, new String[] {sinceVersion, accurateSinceTagValue}, delta, member, accurateSinceTagValue);
							}
						}
					}
				}
			} catch (IllegalArgumentException e) {
				ApiPlugin.log(e);
			}
		} catch (RuntimeException e) {
			ApiPlugin.log(e);
		}
		if(problem != null) {
			fProblems.add(problem);
		}
	}
	
	/**
	 * Creates a marker to denote a problem with the since tag (existence or correctness) for a member
	 * and returns it, or <code>null</code>
	 * @param kind
	 * @param messageargs
	 * @param compilationUnit
	 * @param member
	 * @param version
	 * @return a new {@link IApiProblem} or <code>null</code>
	 */
	private IApiProblem createSinceTagProblem(int kind, final String[] messageargs, final Delta info, final IMember member, final String version) {
		try {
			// create a marker on the member for missing @since tag
			IResource resource = null;
			ICompilationUnit unit = null;
			try {
				unit = member.getCompilationUnit();
				if (unit != null) {
					resource = unit.getCorrespondingResource();
				}
			} catch (JavaModelException e) {
				ApiPlugin.log(e);
			}
			if (resource == null) {
				return null;
			}
			int lineNumber = 1;
			int charStart = 0;
			int charEnd = 1;
			ISourceRange range = member.getNameRange();
			charStart = range.getOffset();
			charEnd = charStart + range.getLength();
			try {
				// unit cannot be null
				IDocument document = Util.getDocument(unit);
				lineNumber = document.getLineOfOffset(charStart);
			} catch (BadLocationException e) {
				ApiPlugin.log(e);
			}
			return ApiProblemFactory.newApiSinceTagProblem(resource.getProjectRelativePath().toPortableString(), 
					messageargs, 
					new String[] {IApiMarkerConstants.MARKER_ATTR_VERSION, IApiMarkerConstants.API_MARKER_ATTR_ID, IApiMarkerConstants.MARKER_ATTR_HANDLE_ID}, 
					new Object[] {version, new Integer(IApiMarkerConstants.SINCE_TAG_MARKER_ID), member.getHandleIdentifier()}, 
					lineNumber, 
					charStart, 
					charEnd, 
					info.getElementType(), 
					kind);
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
		return null;
	}
	
	/**
	 * Creates an {@link IApiProblem} for the given compatibility delta 
	 * @param delta
	 * @param jproject
	 * @param reference
	 * @param component
	 * @return a new compatibility problem or <code>null</code>
	 */
	private IApiProblem createCompatibilityProblem(final IDelta delta, final IJavaProject jproject, final IApiComponent reference, final IApiComponent component) {
		try {
			Version referenceVersion = new Version(reference.getVersion());
			Version componentVersion = new Version(component.getVersion());
			if (referenceVersion.getMajor() < componentVersion.getMajor()) {
				// API breakage are ok in this case
				fBuildState.addBreakingChange(delta);
				return null;
			}
			IResource resource = null;
			IType type = null;
			try {
				//TODO won't work in the headless world
				type = jproject.findType(delta.getTypeName().replace('$', '.'));
			} catch (JavaModelException e) {
				ApiPlugin.log(e);
			}
			if (type == null) {
				//TODO won't work in the headless world
				IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
				if (manifestFile == null) {
					// Cannot retrieve the manifest.mf file
					return null;
				}
				resource = manifestFile;
			} else {
				//TODO won't work in the headless world
				ICompilationUnit unit = type.getCompilationUnit();
				if (unit != null) {
					resource = unit.getCorrespondingResource();
					if (resource == null) {
						return null;
					}
				} else {
					//TODO won't work in the headless world
					IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
					if (manifestFile == null) {
						// Cannot retrieve the manifest.mf file
						return null;
					}
					resource = manifestFile;
				}
			}
			// retrieve line number, char start and char end
			int lineNumber = 1;
			int charStart = -1;
			int charEnd = 1;
			IMember member = Util.getIMember(delta, jproject);
			if (member != null) {
				ISourceRange range = member.getNameRange();
				charStart = range.getOffset();
				charEnd = charStart + range.getLength();
				try {
					IDocument document = Util.getDocument(member.getCompilationUnit());
					lineNumber = document.getLineOfOffset(charStart);
				} catch (BadLocationException e) {
					// ignore
				}
			}
			//TODO won't work in the headless world
			IApiProblem apiProblem = ApiProblemFactory.newApiProblem(resource.getProjectRelativePath().toPortableString(),
					delta.getArguments(),
					new String[] {
						IApiMarkerConstants.MARKER_ATTR_HANDLE_ID,
						IApiMarkerConstants.API_MARKER_ATTR_ID
					},
					new Object[] {
						member == null ? null : member.getHandleIdentifier(),
						new Integer(IApiMarkerConstants.COMPATIBILITY_MARKER_ID),
					},
					lineNumber,
					charStart,
					charEnd,
					IApiProblem.CATEGORY_COMPATIBILITY,
					delta.getElementType(),
					delta.getKind(),
					delta.getFlags());
			return apiProblem;
			
		} catch (CoreException e) {
			ApiPlugin.log(e);
		}
		return null;
	}
	
	/**
	 * Processes a delta to know if we need to check for since tag or version numbering problems
	 * @param jproject
	 * @param delta
	 * @param reference
	 * @param component
	 */
	private void processDelta(final IJavaProject jproject, final IDelta delta, final IApiComponent reference, final IApiComponent component) {
		if (DeltaProcessor.isCompatible(delta)) {
			if (delta.getFlags() != IDelta.EXECUTION_ENVIRONMENT) {
				// we filter EXECUTION ENVIRONMENT deltas
				fBuildState.addCompatibleChange(delta);
			}
			if (delta.getKind() == IDelta.ADDED) {
				int modifiers = delta.getModifiers();
				if (Util.isPublic(modifiers)) {
					// if public, we always want to check @since tags
					switch(delta.getFlags()) {
						case IDelta.TYPE_MEMBER :
						case IDelta.METHOD :
						case IDelta.CONSTRUCTOR :
						case IDelta.ENUM_CONSTANT :
						case IDelta.METHOD_WITH_DEFAULT_VALUE :
						case IDelta.METHOD_WITHOUT_DEFAULT_VALUE :
						case IDelta.FIELD :
						case IDelta.TYPE :
							if (DEBUG) {
								String deltaDetails = "Delta : " + Util.getDetail(delta); //$NON-NLS-1$
								System.out.println(deltaDetails + " is compatible"); //$NON-NLS-1$
							}
							fPendingDeltaInfos.add(delta);
							break;
					}
				} else if (Util.isProtected(modifiers) && !RestrictionModifiers.isExtendRestriction(delta.getRestrictions())) {
					// if protected, we only want to check @since tags if the enclosing class can be subclassed
					switch(delta.getFlags()) {
						case IDelta.TYPE_MEMBER :
						case IDelta.METHOD :
						case IDelta.CONSTRUCTOR :
						case IDelta.ENUM_CONSTANT :
						case IDelta.FIELD :
						case IDelta.TYPE :
							if (DEBUG) {
								String deltaDetails = "Delta : " + Util.getDetail(delta); //$NON-NLS-1$
								System.out.println(deltaDetails + " is compatible"); //$NON-NLS-1$
							}
							fPendingDeltaInfos.add(delta);
							break;
					}
				}
			}
		} else {
			if (delta.getKind() == IDelta.ADDED) {
				// if public, we always want to check @since tags
				switch(delta.getFlags()) {
					case IDelta.TYPE_MEMBER :
					case IDelta.METHOD :
					case IDelta.CONSTRUCTOR :
					case IDelta.ENUM_CONSTANT :
					case IDelta.METHOD_WITH_DEFAULT_VALUE :
					case IDelta.METHOD_WITHOUT_DEFAULT_VALUE :
					case IDelta.FIELD :
						// ensure that there is a @since tag for the corresponding member
						if (delta.getKind() == IDelta.ADDED && Util.isVisible(delta)) {
							if (DEBUG) {
								String deltaDetails = "Delta : " + Util.getDetail(delta); //$NON-NLS-1$
								System.err.println(deltaDetails + " is not compatible"); //$NON-NLS-1$
							}
							fPendingDeltaInfos.add(delta);
						}
				}
			}
			IApiProblem problem = createCompatibilityProblem(delta, jproject, reference, component);
			if(problem != null) {
				if(fProblems.add(problem)) {
					fBuildState.addBreakingChange(delta);
				}
			}
		}
	}
	
	/**
	 * Checks the version number of the API component and creates a problem markers as needed
	 * @param reference
	 * @param component
	 */
	private void checkApiComponentVersion(final IApiComponent reference, final IApiComponent component) {
		if(ignoreComponentVersionCheck() || reference == null || component == null) {
			if(DEBUG) {
				System.out.println("Ignoring component version check"); //$NON-NLS-1$
			}
			return;
		}
		IApiProblem problem = null;
		String refversionval = reference.getVersion();
		String compversionval = component.getVersion();
		Version refversion = new Version(refversionval);
		Version compversion = new Version(compversionval);
		Version newversion = null;
		if (DEBUG) {
			System.out.println("reference version of " + reference.getId() + " : " + refversion); //$NON-NLS-1$ //$NON-NLS-2$
			System.out.println("component version of " + component.getId() + " : " + compversion); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IDelta[] breakingChanges = fBuildState.getBreakingChanges();
		if (breakingChanges.length != 0) {
			// make sure that the major version has been incremented
			if (compversion.getMajor() <= refversion.getMajor()) {
				newversion = new Version(compversion.getMajor() + 1, 0, 0, compversion.getQualifier());
				problem = createVersionProblem(
						IApiProblem.MAJOR_VERSION_CHANGE,
						new String[] {
							compversionval,
							refversionval
						},
						true,
						String.valueOf(newversion),
						collectDetails(breakingChanges));
			}
		} else {
			IDelta[] compatibleChanges = fBuildState.getCompatibleChanges();
			if (compatibleChanges.length != 0) {
				// only new API have been added
				if (compversion.getMajor() != refversion.getMajor()) {
					// major version should be identical
					newversion = new Version(refversion.getMajor(), compversion.getMinor() + 1, 0, compversion.getQualifier());
					problem = createVersionProblem(
							IApiProblem.MAJOR_VERSION_CHANGE_NO_BREAKAGE,
							new String[] {
								compversionval,
								refversionval
							},
							false,
							String.valueOf(newversion),
							collectDetails(compatibleChanges));
				} else if (compversion.getMinor() <= refversion.getMinor()) {
					// the minor version should be incremented
					newversion = new Version(compversion.getMajor(), compversion.getMinor() + 1, 0, compversion.getQualifier());
					problem = createVersionProblem(
							IApiProblem.MINOR_VERSION_CHANGE, 
							new String[] {
								compversionval,
								refversionval
							},
							false,
							String.valueOf(newversion),
							collectDetails(compatibleChanges));
				}
			}
		}
		if(problem != null) {
			fProblems.add(problem);
		}
	}
	
	/**
	 * Collects details from the given delta listing for version problems
	 * @param deltas
	 * @return
	 */
	private String collectDetails(final IDelta[] deltas) {
		StringBuffer buffer = new StringBuffer();
		for (int i = 0, max = deltas.length; i < max ; i++) {
			buffer.append("- "); //$NON-NLS-1$
			buffer.append(deltas[i].getMessage());
		}
		return buffer.toString();
	}
	
	/**
	 * Creates a marker on a manifest file for a version numbering problem and returns it
	 * or <code>null</code> 
	 * @param kind
	 * @param messageargs
	 * @param breakage
	 * @param version
	 * @param description the description of details
	 * @return a new {@link IApiProblem} or <code>null</code>
	 */
	private IApiProblem createVersionProblem(int kind, final String[] messageargs, boolean breakage, String version, String description) {
		IResource manifestFile = Util.getManifestFile(this.fCurrentProject);
		if (manifestFile == null) {
			// Cannot retrieve the manifest.mf file
			return null;
		}
		// this error should be located on the manifest.mf file
		// first of all we check how many api breakage marker are there
		int lineNumber = -1;
		int charStart = 0;
		int charEnd = 1;
		char[] contents = null;
		if (manifestFile.getType() == IResource.FILE) {
			IFile file = (IFile) manifestFile;
			InputStream inputStream = null;
			LineNumberReader reader = null;
			try {
				inputStream = file.getContents(true);
				contents = Util.getInputStreamAsCharArray(inputStream, -1, IApiCoreConstants.UTF_8);
				reader = new LineNumberReader(new BufferedReader(new StringReader(new String(contents))));
				int lineCounter = 0;
				String line = null;
				loop: while ((line = reader.readLine()) != null) {
					lineCounter++;
					if (line.startsWith(Constants.BUNDLE_VERSION)) {
						lineNumber = lineCounter;
						break loop;
					}
				}
			} catch (CoreException e) {
				// ignore
			} catch (IOException e) {
				// ignore
			} finally {
				try {
					if (inputStream != null) {
						inputStream.close();
					}
					if (reader != null) {
						reader.close();
					}
				} catch (IOException e) {
					// ignore
				}
			}
		}
		if (lineNumber != -1 && contents != null) {
			// initialize char start, char end
			int index = CharOperation.indexOf(Constants.BUNDLE_VERSION.toCharArray(), contents, true);
			loop: for (int i = index + Constants.BUNDLE_VERSION.length() + 1, max = contents.length; i < max; i++) {
				char currentCharacter = contents[i];
				if (CharOperation.isWhitespace(currentCharacter)) {
					continue;
				}
				charStart = i;
				break loop;
			}
			loop: for (int i = charStart + 1, max = contents.length; i < max; i++) {
				switch(contents[i]) {
					case '\r' :
					case '\n' :
						charEnd = i;
						break loop;
				}
			}
		} else {
			lineNumber = 1;
		}
		return ApiProblemFactory.newApiVersionNumberProblem(manifestFile.getProjectRelativePath().toPortableString(), 
				messageargs, 
				new String[] {
					IApiMarkerConstants.MARKER_ATTR_VERSION,
					IApiMarkerConstants.API_MARKER_ATTR_ID,
					IApiMarkerConstants.VERSION_NUMBERING_ATTR_DESCRIPTION,
				}, 
				new Object[] {
					version,
					new Integer(IApiMarkerConstants.VERSION_NUMBERING_MARKER_ID),
					description
				}, 
				lineNumber, 
				charStart, 
				charEnd, 
				IElementDescriptor.T_RESOURCE, 
				kind);
	}
	
	/**
	 * Checks to see if there is a default API profile set in the workspace,
	 * if not create a marker
	 */
	private void checkDefaultBaselineSet() {
		if(ignoreDefaultBaselineCheck()) {
			if(DEBUG) {
				System.out.println("Ignoring check for default API baseline"); //$NON-NLS-1$
			}
			return;
		}
		if(ApiPlugin.getDefault().getApiProfileManager().getDefaultApiProfile() == null) {
			if(DEBUG) {
				System.out.println("Checking if the default api baseline is set"); //$NON-NLS-1$
			}
			IApiProblem problem = ApiProblemFactory.newApiProfileProblem(fCurrentProject.getProjectRelativePath().toPortableString(), 
					null, 
					new String[] {IApiMarkerConstants.API_MARKER_ATTR_ID}, 
					new Object[] {new Integer(IApiMarkerConstants.DEFAULT_API_PROFILE_MARKER_ID)}, 
					-1, 
					-1, 
					-1,  
					IElementDescriptor.T_RESOURCE, 
					IApiProblem.API_PROFILE_MISSING);
			fProblems.add(problem);
		}
	}
	
	/**
	 * Updates the work done on the monitor by 1 tick and polls to see if the monitor has been cancelled
	 * @param monitor
	 * @throws OperationCanceledException if the monitor has been cancelled
	 */
	private void updateMonitor(IProgressMonitor monitor) throws OperationCanceledException {
		if(monitor != null) {
			monitor.worked(1);
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
		}
	}
}
