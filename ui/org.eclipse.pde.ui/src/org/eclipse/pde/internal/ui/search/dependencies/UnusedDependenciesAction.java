/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.search.dependencies;

import org.eclipse.core.runtime.jobs.*;
import org.eclipse.jface.action.*;
import org.eclipse.pde.core.plugin.*;
import org.eclipse.pde.internal.ui.*;
import org.eclipse.ui.progress.*;

public class UnusedDependenciesAction extends Action {
	
	private static String ACTION_NAME = "UnusedDependencies.action"; //$NON-NLS-1$

	private IPluginModelBase fModel;

	public UnusedDependenciesAction(IPluginModelBase model) {
		fModel = model;
		setText(PDEPlugin.getResourceString(ACTION_NAME)); 
	}

	public void run() {
		Job job = new UnusedDependenciesJob("Find unused dependencies", fModel);
		job.setUser(true);
		job.setProperty(IProgressConstants.ICON_PROPERTY, PDEPluginImages.DESC_PSEARCH_OBJ.createImage());
		job.schedule();
	}

}
