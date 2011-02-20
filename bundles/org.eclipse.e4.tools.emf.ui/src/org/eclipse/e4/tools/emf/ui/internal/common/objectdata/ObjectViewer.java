package org.eclipse.e4.tools.emf.ui.internal.common.objectdata;

import java.util.Collection;
import java.util.Collections;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.core.databinding.observable.value.IValueChangeListener;
import org.eclipse.core.databinding.observable.value.ValueChangeEvent;
import org.eclipse.e4.tools.emf.ui.internal.Messages;
import org.eclipse.e4.tools.emf.ui.internal.ResourceProvider;
import org.eclipse.e4.tools.emf.ui.internal.common.ModelEditor;
import org.eclipse.e4.tools.services.IResourcePool;
import org.eclipse.emf.databinding.EMFProperties;
import org.eclipse.emf.databinding.IEMFValueProperty;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;

public class ObjectViewer {
	public TreeViewer createViewer(Composite parent, EStructuralFeature feature, IObservableValue master, IResourcePool resourcePool, Messages messages) {
		final TreeViewer viewer = new TreeViewer(parent);
		viewer.setContentProvider(new ContentProviderImpl());
		viewer.setLabelProvider(new LabelProviderImpl(resourcePool));
		viewer.setComparator(new ViewerComparatorImpl());
		IEMFValueProperty property = EMFProperties.value(feature);
		IObservableValue value = property.observeDetail(master);
		value.addValueChangeListener(new IValueChangeListener() {

			public void handleValueChange(ValueChangeEvent event) {
				if (event.diff.getNewValue() != null) {
					viewer.setInput(Collections.singleton(new JavaObject(event.diff.getNewValue())));
					viewer.expandToLevel(2);
				} else {
					viewer.setInput(Collections.emptyList());
				}
			}
		});
		new TooltipSupportImpl(viewer, ToolTip.NO_RECREATE, false, resourcePool, messages);
		return viewer;
	}

	class TooltipSupportImpl extends ColumnViewerToolTipSupport {
		private IResourcePool resourcePool;
		private Messages messages;

		protected TooltipSupportImpl(ColumnViewer viewer, int style, boolean manualActivation, IResourcePool resourcePool, Messages messages) {
			super(viewer, style, manualActivation);
			this.resourcePool = resourcePool;
			this.messages = messages;
		}

		@Override
		protected Composite createViewerToolTipContentArea(Event event, ViewerCell cell, Composite parent) {
			JavaAttribute attribute = (JavaAttribute) cell.getElement();

			Composite container = new Composite(parent, SWT.NONE);
			container.setBackground(container.getDisplay().getSystemColor(SWT.COLOR_WHITE));
			container.setLayout(new GridLayout(2, false));

			{
				Composite headerContainer = new Composite(container, SWT.NONE);
				headerContainer.setBackgroundMode(SWT.INHERIT_DEFAULT);
				headerContainer.setData(ModelEditor.CSS_CLASS_KEY, "headerSectionContainer"); //$NON-NLS-1$
				GridLayout fl = new GridLayout(2, false);
				fl.marginHeight = 5;
				fl.marginWidth = 5;
				headerContainer.setLayout(fl);
				headerContainer.setLayoutData(new GridData(GridData.FILL, GridData.BEGINNING, true, false, 2, 1));

				Label iconLabel = new Label(headerContainer, SWT.NONE);
				iconLabel.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_bullet_go));

				Label textLabel = new Label(headerContainer, SWT.NONE);
				textLabel.setText(attribute.getName());
				textLabel.setData(ModelEditor.CSS_CLASS_KEY, "sectionHeader"); //$NON-NLS-1$
			}

			{
				Label l = new Label(container, SWT.NONE);
				l.setText(messages.ObjectViewer_Tooltip_Value);

				l = new Label(container, SWT.NONE);
				l.setText(attribute.getValue());
			}

			{
				Label l = new Label(container, SWT.NONE);
				l.setText(messages.ObjectViewer_Tooltip_InjectionKey);

				l = new Label(container, SWT.NONE);
				l.setText(attribute.getContextKey());
			}

			return container;
		}
	}

	class ViewerComparatorImpl extends ViewerComparator {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			if (e1 instanceof JavaAttribute) {
				if (e2 instanceof JavaAttribute) {
					JavaAttribute a1 = (JavaAttribute) e1;
					JavaAttribute a2 = (JavaAttribute) e2;

					if (a1.isStatic() && !a2.isStatic()) {
						return -1;
					} else if (!a1.isStatic() && a2.isStatic()) {
						return 1;
					}

					int rv = Integer.valueOf(a1.getAccessLevel().value).compareTo(a2.getAccessLevel().value);
					if (rv == 0) {
						return a1.getName().compareTo(a2.getName());
					} else {
						return rv;
					}
				} else {
					return -1;
				}
			}
			return super.compare(viewer, e1, e2);
		}
	}

	class LabelProviderImpl extends StyledCellLabelProvider {

		private IResourcePool resourcePool;

		public LabelProviderImpl(IResourcePool resourcePool) {
			this.resourcePool = resourcePool;
		}

		@Override
		public String getToolTipText(Object element) {
			if (element instanceof JavaAttribute && ((JavaAttribute) element).isInjected()) {
				return "Show Tooltip"; //$NON-NLS-1$
			}
			return super.getToolTipText(element);
		}

		@Override
		public void update(ViewerCell cell) {
			if (cell.getElement() instanceof JavaObject) {
				JavaObject o = (JavaObject) cell.getElement();
				cell.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_class_obj));
				cell.setText(o.getName());
			} else if (cell.getElement() instanceof JavaAttribute) {
				JavaAttribute o = (JavaAttribute) cell.getElement();
				StyledString string = new StyledString();
				if (o.isInjected()) {
					string.append("<injected> ", StyledString.COUNTER_STYLER); //$NON-NLS-1$
				}
				string.append(o.getName());
				string.append(" : " + o.getType() + " - " + o.getValue(), StyledString.DECORATIONS_STYLER); //$NON-NLS-1$ //$NON-NLS-2$
				cell.setText(string.getString());
				cell.setStyleRanges(string.getStyleRanges());
				switch (o.getAccessLevel()) {
				case PUBLIC:
					cell.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_field_public_obj));
					break;
				case PRIVATE:
					cell.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_field_private_obj));
					break;
				case DEFAULT:
					cell.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_field_default_obj));
					break;
				default:
					cell.setImage(resourcePool.getImageUnchecked(ResourceProvider.IMG_Obj16_field_protected_obj));
					break;
				}
			}

			super.update(cell);
		}
	}

	class ContentProviderImpl implements ITreeContentProvider {

		public void dispose() {

		}

		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {

		}

		public Object[] getElements(Object inputElement) {
			return ((Collection<?>) inputElement).toArray();

		}

		public Object[] getChildren(Object parentElement) {
			if (parentElement instanceof JavaObject) {
				return ((JavaObject) parentElement).getAttributes().toArray();
			} else if (parentElement instanceof JavaAttribute) {
				return ((JavaAttribute) parentElement).getAttributes().toArray();
			}
			return new Object[0];
		}

		public Object getParent(Object element) {
			// TODO Auto-generated method stub
			return null;
		}

		public boolean hasChildren(Object element) {
			return getChildren(element).length > 0;
		}

	}
}
