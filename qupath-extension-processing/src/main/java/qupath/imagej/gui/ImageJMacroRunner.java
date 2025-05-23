/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2024 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.imagej.gui;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.utils.FXUtils;
import qupath.imagej.tools.IJTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.gui.images.servers.ChannelDisplayTransformServer;
import qupath.lib.gui.tools.GuiTools;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.RoiTools.CombineOp;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import qupathj.QuPath_Send_Overlay_to_QuPath;

/**
 * QuPath plugin for running ImageJ macros &amp; returning detected regions.
 * 
 * @author Pete Bankhead
 * @deprecated since v0.6.0; use {@link qupath.imagej.gui.scripts.ImageJScriptRunner} and
 *             {@link qupath.imagej.gui.scripts.ImageJScriptRunnerController }instead.
 */
@Deprecated
public class ImageJMacroRunner extends AbstractPlugin<BufferedImage> {

	private static Logger logger = LoggerFactory.getLogger(ImageJMacroRunner.class);

	private QuPathGUI qupath;
	private ParameterList params;
	
	private String macroText = null;

	private transient Stage dialog;
		
	/**
	 * Constructor.
	 * @param qupath QuPath instance where the command should be installed.
	 */
	public ImageJMacroRunner(final QuPathGUI qupath) {
		this.qupath = qupath;
	}
	

	@Override
	public String getName() {
		return "ImageJ macro runner";
	}

	@Override
	public String getDescription() {
		return "Apply ImageJ macros to selected regions of interest";
	}

	@Override
	public boolean runPlugin(final TaskRunner runner, final ImageData<BufferedImage> imageData, final String arg) {
		if (!parseArgument(imageData, arg))
			return false;

		if (dialog == null) {
			dialog = new Stage();
			dialog.initOwner(qupath.getStage());
			dialog.setTitle("ImageJ macro runner");
			FXUtils.addCloseWindowShortcuts(dialog);

			BorderPane pane = new BorderPane();

			if (arg != null)
				macroText = arg;

			// Create text area
			final TextArea textArea = new TextArea();
			textArea.setPrefRowCount(12);
			textArea.setPrefSize(400, 400);
			textArea.setWrapText(true);
			textArea.setFont(Font.font("Courier"));
			if (macroText != null)
				textArea.setText(macroText);
			BorderPane panelMacro = new BorderPane();
			panelMacro.setCenter(textArea);


			ParameterPanelFX parameterPanel = new ParameterPanelFX(getParameterList(imageData));
			panelMacro.setBottom(parameterPanel.getPane());


			// Create button panel
			Button btnRun = new Button("Run");
			btnRun.setOnAction(e -> {

					macroText = textArea.getText().trim();
					if (macroText.isEmpty())
						return;

					// TODO: Consider that we're requesting a new ImageData here (probably right, but need to check)
					var viewer = qupath.getViewer();
					var imageDataLocal = viewer.getImageData();
					PathObjectHierarchy hierarchy = imageDataLocal.getHierarchy();
					PathObject pathObject = hierarchy.getSelectionModel().singleSelection() ? hierarchy.getSelectionModel().getSelectedObject() : null;
					if (pathObject instanceof PathAnnotationObject || pathObject instanceof TMACoreObject) {
						SwingUtilities.invokeLater(() -> {
							runMacro(params,
									imageDataLocal,
									viewer.getImageDisplay(), pathObject, macroText);
						});
					} else {
						// Run in a background thread
						Collection<? extends PathObject> parents = getParentObjects(imageDataLocal);
						if (parents.isEmpty()) {
							Dialogs.showErrorNotification("ImageJ macro runner", "No annotation or TMA core objects selected!");
							return;
						}
						
						List<Runnable> tasks = new ArrayList<>();
						for (PathObject parent : parents)
							addRunnableTasks(imageDataLocal, parent, tasks);
						
						qupath.getThreadPoolManager().submitShortTask(() -> {
							runner.runTasks(tasks);
							imageDataLocal.getHierarchy().fireHierarchyChangedEvent(ImageJMacroRunner.this);
						});
					}
			});
			Button btnClose = new Button("Close");
			btnClose.setOnAction(e -> dialog.hide());
			
			GridPane panelButtons = GridPaneUtils.createRowGridControls(btnRun, btnClose);
			
			pane.setCenter(panelMacro);
			pane.setBottom(panelButtons);
			panelButtons.setPadding(new Insets(5, 0, 0, 0));
			
			pane.setPadding(new Insets(10, 10, 10, 10));
			dialog.setScene(new Scene(pane));
		}
		dialog.show();
		return true;
	}



	static void runMacro(final ParameterList params, final ImageData<BufferedImage> imageData, final ImageDisplay imageDisplay, final PathObject pathObject, final String macroText) {

		// Don't try if interrupted
		if (Thread.currentThread().isInterrupted()) {
			logger.warn("Skipping macro for {} - thread interrupted", pathObject);
			return;
		}
		
		PathImage<ImagePlus> pathImage;

		// Extract parameters
		double downsampleFactor = params.getDoubleParameterValue("downsampleFactor");
		boolean sendROI = params.getBooleanParameterValue("sendROI");
		boolean sendOverlay = params.getBooleanParameterValue("sendOverlay");
		ROI pathROI = pathObject.getROI();		
		ImageDisplay imageDisplay2 = params.containsKey("useTransform") && Boolean.TRUE.equals(params.getBooleanParameterValue("useTransform")) ? imageDisplay : null;
		
		ImageServer<BufferedImage> server = imageDisplay2 == null || imageDisplay2.availableChannels().isEmpty() ? imageData.getServer() : ChannelDisplayTransformServer.createColorTransformServer(imageData.getServer(), imageDisplay.availableChannels());
		
		RegionRequest region = RegionRequest.createInstance(imageData.getServer().getPath(), downsampleFactor, pathROI);
		// Check the size of the region to extract - abort if it is too large of if ther isn't enough RAM
		try {
			IJTools.isMemorySufficient(region, imageData);
		} catch (Exception e1) {
			Dialogs.showErrorMessage("ImageJ macro error", e1.getMessage());
			return;
		}
		
		try {
			if (sendOverlay)
				pathImage = IJExtension.extractROIWithOverlay(server, pathObject, imageData.getHierarchy(), region, sendROI, null);
			else
				pathImage = IJExtension.extractROI(server, pathObject, region, sendROI);
		} catch (IOException e) {
            logger.error("Unable to extract image region {}", region, e);
			return;
		}

		// Determine a sensible argument to pass
		String argument;
		if (pathObject instanceof TMACoreObject || !pathObject.hasROI())
			argument = pathObject.getDisplayedName();
		else
			argument = String.format("Region (%d, %d, %d, %d)", region.getX(), region.getY(), region.getWidth(), region.getHeight());

		// Actually run the macro
		final ImagePlus imp = pathImage.getImage();
		imp.setProperty("QuPath region", argument);
		WindowManager.setTempCurrentImage(imp);
		IJExtension.getImageJInstance(); // Ensure we've requested an instance, since this also loads any required extra plugins
		
		// TODO: Pay attention to how threading should be done... I think Swing EDT ok?
		try {
			boolean cancelled = false;
			ImagePlus impResult = null;
			try {
				IJ.redirectErrorMessages();
				Interpreter interpreter = new Interpreter();
				impResult = interpreter.runBatchMacro(macroText, imp);

				// If we had an error, return
				if (interpreter.wasError()) {
					Thread.currentThread().interrupt();
					return;
				}

				// Get the resulting image, if available
				if (impResult == null)
					impResult = WindowManager.getCurrentImage();
			} catch (RuntimeException e) {
				logger.error(e.getLocalizedMessage());
				//			DisplayHelpers.showErrorMessage("ImageJ macro error", e.getLocalizedMessage());
				Thread.currentThread().interrupt();
				cancelled = true;
			} finally {
				//		IJ.runMacro(macroText, argument);
				WindowManager.setTempCurrentImage(null);
//					IJ.run("Close all");
			}
			if (cancelled)
				return;


			// Get the current image when the macro has finished - which may or may not be the same as the original
			if (impResult == null)
				impResult = imp;


			boolean changes = false;
			if (params.getBooleanParameterValue("clearObjects") && pathObject.hasChildObjects()) {
				pathObject.clearChildObjects();
				changes = true;
			}
			if (params.getBooleanParameterValue("getROI") && impResult.getRoi() != null) {
				Roi roi = impResult.getRoi();
				Calibration cal = impResult.getCalibration();
				PathObject pathObjectNew = roi == null ? null : IJTools.convertToAnnotation(roi, cal.xOrigin, cal.yOrigin, downsampleFactor, region.getImagePlane());
				if (pathObjectNew != null) {
					// If necessary, trim any returned annotation
					if (pathROI != null && !(pathROI instanceof RectangleROI) && pathObjectNew.isAnnotation() && RoiTools.isShapeROI(pathROI) && RoiTools.isShapeROI(pathObjectNew.getROI())) {
						ROI roiNew = RoiTools.combineROIs(pathROI, pathObjectNew.getROI(), CombineOp.INTERSECT);
						((PathAnnotationObject)pathObjectNew).setROI(roiNew);
					}
					// Only add if we have something
					if (pathObjectNew.getROI() instanceof LineROI || !pathObjectNew.getROI().isEmpty()) {
						pathObject.addChildObject(pathObjectNew);
						changes = true;
					}
				}
			}

			boolean exportAsDetection = Objects.equals(params.getChoiceParameterValue("getOverlayAs"), "Detections");
			if (params.getBooleanParameterValue("getOverlay") && impResult.getOverlay() != null) {
				var overlay = impResult.getOverlay();
				List<PathObject> childObjects = QuPath_Send_Overlay_to_QuPath.createObjectsFromROIs(imp, Arrays.asList(overlay.toArray()), downsampleFactor, exportAsDetection, true, region.getImagePlane());
				if (!childObjects.isEmpty()) {
					pathObject.addChildObjects(childObjects);
					changes = true;
				}
			}

			if (changes) {
				Platform.runLater(() -> imageData.getHierarchy().fireHierarchyChangedEvent(null));
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}

	}



	@Override
	protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {
		if (imageData == null)
			return false;
		if (arg != null && arg.length() > 0)
			macroText = arg;
		return true;
	}

	@Override
	public String getLastResultsDescription() {
		// TODO Auto-generated method stub
		return null;
	}


	ParameterList getParameterList(final ImageData<BufferedImage> imageData) {
		if (params == null)
			params = new ParameterList()
				.addTitleParameter("Setup")
				.addDoubleParameter("downsampleFactor", "Downsample factor", 1)
				//			.addBooleanParameter("useTransform", "Send color transformed image", true) // Not supported in batch mode, so disable option to avoid confusion
				.addBooleanParameter("sendROI", "Send ROI to ImageJ", true)
				.addBooleanParameter("sendOverlay", "Send overlay to ImageJ", true)
				.addBooleanParameter("doParallel", "Do parallel processing (experimental)", false)
				.addTitleParameter("Results")
				.addBooleanParameter("clearObjects", "Clear current child objects", false)
				.addBooleanParameter("getROI", "Create annotation from ImageJ ROI", false)
				.addBooleanParameter("getOverlay", "Get objects from ImageJ overlay", false)
				.addChoiceParameter("getOverlayAs", "Get objects as", "Detections", Arrays.asList("Detections", "Annotations"), "Return ROIs from the ImageJ overlay as QuPath detections or annotations")
				;
		return params;
	}


	protected Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		ArrayList<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathAnnotationObject.class);
		return list;
	}

	@Override
	protected void addRunnableTasks(final ImageData<BufferedImage> imageData, final PathObject parentObject, final List<Runnable> tasks) {
		final ParameterList params = getParameterList(imageData);
		boolean doParallel = Boolean.TRUE.equals(params.getBooleanParameterValue("doParallel"));
		tasks.add(new Runnable() {

			@Override
			public void run() {
				if (Thread.currentThread().isInterrupted()) {
					logger.warn("Execution interrupted - skipping {}", parentObject);
					return;
				}
				if (SwingUtilities.isEventDispatchThread() || doParallel)
					runMacro(params, imageData, null, parentObject, macroText); // TODO: Deal with logging macro text properly
				else {
					try {
						SwingUtilities.invokeAndWait(() -> runMacro(params, imageData, null, parentObject, macroText));
					} catch (InvocationTargetException | InterruptedException e) {
						logger.error(e.getMessage(), e);
					} // TODO: Deal with logging macro text properly
				}
			}

		});
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		// Try to get currently-selected objects
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		List<PathObject> pathObjects = hierarchy.getSelectionModel().getSelectedObjects().stream()
				.filter(p -> p.isAnnotation() || p.isTMACore()).toList();
		if (pathObjects.isEmpty()) {
			if (GuiTools.promptForParentObjects(this.getName(), imageData, false, getSupportedParentObjectClasses()))
				pathObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());
		}
		return pathObjects;
	}
}