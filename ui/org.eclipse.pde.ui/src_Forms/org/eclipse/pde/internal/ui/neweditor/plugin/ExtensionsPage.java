/*
 * Created on Jan 27, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package org.eclipse.pde.internal.ui.neweditor.plugin;
import org.eclipse.jface.action.Action;
import org.eclipse.pde.internal.core.ischema.ISchemaElement;
import org.eclipse.pde.internal.ui.PDEPluginImages;
import org.eclipse.pde.internal.ui.neweditor.*;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.forms.*;
import org.eclipse.ui.forms.editor.FormEditor;
import org.eclipse.ui.forms.widgets.*;
/**
 * @author dejan
 * 
 * To change the template for this generated type comment go to Window -
 * Preferences - Java - Code Generation - Code and Comments
 */
public class ExtensionsPage extends PDEFormPage {
	public static final String PAGE_ID = "extensions";
	private ExtensionsBlock block;
	private ExtensionsSection section;
	
	public class ExtensionsBlock extends PDEMasterDetailsBlock implements IDetailsPageProvider {
		public ExtensionsBlock() {
			super(ExtensionsPage.this);
		}
		protected PDESection createMasterSection(ManagedForm managedForm,
				Composite parent) {
			section = new ExtensionsSection(getPage(), parent);
			return section;
		}
		protected void registerPages(DetailsPart detailsPart) {
			// register static page for the extensions
			detailsPart.registerPage(DummyExtension.class, new ExtensionDetails());
			// register a dynamic provider for elements
			detailsPart.setPageProvider(this);
		}
		public Object getPageKey(Object object) {
			if (object instanceof DummyExtension)
				return DummyExtension.class;
			if (object instanceof DummyExtensionElement) {
				DummyExtensionElement e = (DummyExtensionElement)object;
				return e.getSchemaElement();
			}
			return object.getClass();
		}
		public IDetailsPage getPage(Object object) {
			if (object instanceof ISchemaElement)
				return new ExtensionElementDetails((ISchemaElement)object);
			return null;
		}
		protected void createToolBarActions(ManagedForm managedForm) {
			final ScrolledForm form = managedForm.getForm();
			Action collapseAction = new Action("col") {
				public void run() {
					section.collapseAll();
				}
			};
			collapseAction.setToolTipText("Collapse All");
			collapseAction.setImageDescriptor(PDEPluginImages.DESC_COLLAPSE_ALL);
			collapseAction.setHoverImageDescriptor(PDEPluginImages.DESC_COLLAPSE_ALL_HOVER);
			form.getToolBarManager().add(collapseAction);
			super.createToolBarActions(managedForm);
		}
	}
	/**
	 * @param editor
	 * @param id
	 * @param title
	 */
	public ExtensionsPage(FormEditor editor) {
		super(editor, PAGE_ID, "Extensions");
		block = new ExtensionsBlock();
	}
	protected void createFormContent(ManagedForm managedForm) {
		super.createFormContent(managedForm);
		ScrolledForm form = managedForm.getForm();
		FormToolkit toolkit = managedForm.getToolkit();
		form.setText("Extensions");
		block.createContent(managedForm);
	}
}