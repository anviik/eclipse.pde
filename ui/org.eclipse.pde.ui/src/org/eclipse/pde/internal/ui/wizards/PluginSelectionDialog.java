/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.wizards;

import java.util.HashSet;

import org.eclipse.pde.core.plugin.IFragment;
import org.eclipse.pde.core.plugin.IFragmentModel;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModel;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.ui.PDEPlugin;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

public class PluginSelectionDialog extends ElementListSelectionDialog {

	public PluginSelectionDialog(Shell parentShell, boolean includeFragments,
			boolean multipleSelection) {
		this(parentShell, getElements(includeFragments), multipleSelection);
	}

	public PluginSelectionDialog(Shell parentShell, IPluginModelBase[] models,
			boolean multipleSelection) {
		super(parentShell, PDEPlugin.getDefault().getLabelProvider());
		setTitle(PDEUIMessages.PluginSelectionDialog_title);
		setMessage(PDEUIMessages.PluginSelectionDialog_message);
		setElements(models);
		setMultipleSelection(multipleSelection);
		PDEPlugin.getDefault().getLabelProvider().connect(this);
	}

	public boolean close() {
		PDEPlugin.getDefault().getLabelProvider().disconnect(this);
		return super.close();
	}

	private static IPluginModelBase[] getElements(boolean includeFragments) {
		return PluginRegistry.getActiveModels(includeFragments);
	}

	public static HashSet getExistingImports(IPluginModelBase model) {
		HashSet existingImports = new HashSet();
		addSelfAndDirectImports(existingImports, model);
		if (model instanceof IFragmentModel) {
			IFragment fragment = ((IFragmentModel)model).getFragment();
			IPluginModelBase host = PluginRegistry.findModel(fragment.getPluginId());
			if (host instanceof IPluginModel) {
				addSelfAndDirectImports(existingImports, host);
			}
		}
		return existingImports;
	}

	private static void addSelfAndDirectImports(HashSet set, IPluginModelBase model) {
		set.add(model.getPluginBase().getId());
		IPluginImport[] imports = model.getPluginBase().getImports();
		for (int i = 0; i < imports.length; i++) {
			String id = imports[i].getId();
			if (set.add(id)) {
				addReexportedImport(set, id);
			}
		}
	}

	private static void addReexportedImport(HashSet set, String id) {
		IPluginModelBase model = PluginRegistry.findModel(id);
		if (model != null) {
			IPluginImport[] imports = model.getPluginBase().getImports();
			for (int i = 0; i < imports.length; i++) {
				if (imports[i].isReexported() && set.add(imports[i].getId())) {
					addReexportedImport(set, imports[i].getId());
				}
			}
		}
	}
}
