package org.yanhuang.plugins.intellij.exportjar.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yanhuang.plugins.intellij.exportjar.ExportPacker;
import org.yanhuang.plugins.intellij.exportjar.model.ExportOptions;
import org.yanhuang.plugins.intellij.exportjar.model.SettingHistory;
import org.yanhuang.plugins.intellij.exportjar.model.SettingSelectFile;
import org.yanhuang.plugins.intellij.exportjar.model.UISizes;
import org.yanhuang.plugins.intellij.exportjar.settings.HistoryDao;
import org.yanhuang.plugins.intellij.exportjar.utils.CommonUtils;
import org.yanhuang.plugins.intellij.exportjar.utils.Constants;
import org.yanhuang.plugins.intellij.exportjar.utils.MessagesUtils;
import org.yanhuang.plugins.intellij.exportjar.utils.UpgradeManager;

import com.intellij.openapi.MnemonicHelper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaDirectoryService;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPackage;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SeparatorFactory;
import com.intellij.ui.TitledSeparator;
import com.intellij.util.Consumer;
import com.intellij.util.ui.components.BorderLayoutPanel;

import static com.intellij.openapi.ui.Messages.getWarningIcon;
import static com.intellij.openapi.ui.Messages.showErrorDialog;
import static com.intellij.util.ui.JBUI.Panels.simplePanel;
import static javax.swing.BorderFactory.createEmptyBorder;
import static org.yanhuang.plugins.intellij.exportjar.utils.CommonUtils.backgroundRunWithoutLock;
import static org.yanhuang.plugins.intellij.exportjar.utils.CommonUtils.findModule;
import static org.yanhuang.plugins.intellij.exportjar.utils.CommonUtils.runInBgtWithReadLockAndWait;

/**
 * export jar settings dialog (link to SettingDialog.form)
 * <li><b>In UIDesigner: export options JCheckBox's name is same as HistoryData.ExportOptions</b></li>
 * <li><b>If modal dialog, should extends DialogWrapper, or else throw exception when setVisible(true)</b></li>
 */
public class SettingDialog extends DialogWrapper {
	@Nullable
	protected VirtualFile[] selectedFiles;
	protected final Project project;
	private JPanel contentPane;
	private JButton buttonOK;
	private JButton buttonCancel;
	protected JCheckBox exportJavaFileCheckBox;
	private JCheckBox exportClassFileCheckBox;
	private JCheckBox exportTestFileCheckBox;
	private JCheckBox exportAddDirectoryCheckBox;
	protected JComboBox<String> outPutJarFileComboBox;
	private JButton selectJarFileButton;
	private JPanel settingPanel;
	protected JPanel fileListPanel;
	private JButton debugButton;
	private JPanel actionPanel;
	protected JPanel optionsPanel;
	private JPanel jarFilePanel;
	private JBSplitter fileListSettingSplitPanel;
	private JPanel templatePanel;
	protected JCheckBox templateEnableCheckBox;
	protected JComboBox<String> templateSelectComBox;
	protected JButton templateSaveButton;
	protected JButton templateDelButton;
	private JPanel templateTitlePanel;
	private JPanel outputJarTitlePanel;
	private JPanel optionTitlePanel;
	protected FileListDialog fileListDialog;
	private BorderLayoutPanel fileListLabel;
	private final HistoryDao historyDao = new HistoryDao();

	private final TemplateEventHandler templateHandler = new TemplateEventHandler(this);

	public SettingDialog(Project project, @Nullable VirtualFile[] selectedFiles, @Nullable String template) {
		super(true);
		MessagesUtils.getMessageView(project);//register message tool window to avoid pack error
		this.project = project;
		this.selectedFiles = selectedFiles;
		this.getPeer().setContentPane(contentPane);
		getRootPane().setDefaultButton(buttonOK);
		this.buttonOK.addActionListener(e -> onOK());
		this.buttonCancel.addActionListener(e -> onCancel());
		this.debugButton.addActionListener(e -> onDebug());

		this.contentPane.registerKeyboardAction(e -> onCancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		this.selectJarFileButton.addActionListener(this::onSelectJarFileButton);

		migrateSavedHistory();
		historyDao.initV2023();
		createFileListTree();
		updateSettingPanelComponents();
		updateFileListSettingSplitPanel();
//        uiDebug();
		updateComponentState(template);
		updateTemplateUiState();
		initDefaultOutputJarPath(selectedFiles); // ← 就放这里
		this.templateEnableCheckBox.addItemListener(templateHandler::templateEnableChanged);
		this.templateSaveButton.addActionListener(templateHandler::saveTemplate);
		this.templateDelButton.addActionListener(templateHandler::delTemplate);
		this.templateSelectComBox.addItemListener(templateHandler::templateSelectChanged);
		this.outPutJarFileComboBox.addItemListener(templateHandler::exportJarChanged);
		initMnemonics();
	}

	private void initDefaultOutputJarPath(VirtualFile[] selectedFiles) {
		if (outPutJarFileComboBox == null) {
			return;
		}

		ComboBoxModel<String> model = outPutJarFileComboBox.getModel();
		if (!(model instanceof DefaultComboBoxModel)) {
			return;
		}

		DefaultComboBoxModel<String> comboModel =
				(DefaultComboBoxModel<String>) model;

		// 如果已经有选中值（历史 / 模板），不覆盖
		Object selected = comboModel.getSelectedItem();
		if (selected != null && !selected.toString().trim().isEmpty()) {
			// return;
		}

		// ===== 1. 生成时间前缀 =====
		SimpleDateFormat datetimeFormat = new SimpleDateFormat("yyyyMMddHHmm");
		String time = datetimeFormat.format(System.currentTimeMillis());

		// ===== 2. jar 名称 =====
		String jarName = "";
		if (selectedFiles.length == 1 && !selectedFiles[0].isDirectory()) {//如果选中一个类，则以类名为默认jar名称
			jarName = selectedFiles[0].getName();
		} else {
			List<String> names = new ArrayList<>();
			PsiManager psiManager = PsiManager.getInstance(project);
			for (VirtualFile file : selectedFiles) {
				PsiDirectory psiDirectory = file.isDirectory()
						? psiManager.findDirectory(file)
						: psiManager.findDirectory(file.getParent());
				if (psiDirectory != null) {
					PsiPackage psiPackage = JavaDirectoryService.getInstance().getPackage(psiDirectory);
					names.add(psiPackage.getQualifiedName());
				}
			}
			jarName = getTheSameStart(names);
			if (jarName.isEmpty()) {
				jarName = "multiModule";
			}
			if (jarName.endsWith(".")) {
				jarName = jarName.substring(0, jarName.lastIndexOf("."));
			}
		}
		jarName = time + "-" + jarName + "-patch.jar";

		// ===== 3. Desktop/<projectName> 路径 =====
		String userHome = System.getProperty("user.home");
		String separator = System.getProperty("file.separator");

		String defaultOutputDir =
				userHome + separator + "Desktop" + separator + project.getName();

		String fullJarPath = defaultOutputDir + separator + jarName;

		// ===== 4. 设置到 ComboBox =====
		comboModel.insertElementAt(fullJarPath, 0);
		comboModel.setSelectedItem(fullJarPath);

		Object jar = outPutJarFileComboBox.getSelectedItem();
		templateHandler.exportJarChanged(
				new ItemEvent(outPutJarFileComboBox, ItemEvent.ITEM_STATE_CHANGED, jar, ItemEvent.SELECTED)
		);
	}
	private String getTheSameStart(List<String> strings) {
		if (strings == null || strings.size() == 0) {
			return "";
		}
		int max = 888888;
		for (String string : strings) {
			if (string.length() < max) {
				max = string.length();
			}
		}
		StringBuilder sb = new StringBuilder();
		HashSet set = new HashSet();
		for (int i = 0; i < max; i++) {
			for (String string : strings) {
				set.add(string.charAt(i));
			}
			if (set.size() == 1) {
				sb.append(set.iterator().next());
			} else {
				break;
			}
			set.clear();
		}
		return sb.toString();
	}


	private void updateComponentState(String template) {
		this.setResizable(true);
		final SettingHistory history = historyDao.readOrDefault();
		final Dimension splitPanelSize =
				Optional.ofNullable(history.getUi()).map(UISizes::getFileSettingSplitPanel).orElse(Constants.fileListSettingSplitPanelSize);
		final float splitPanelRatio =
				Optional.ofNullable(history.getUi()).map(UISizes::getFileSettingSplitRatio).orElse(0.5f);
		this.fileListSettingSplitPanel.setPreferredSize(splitPanelSize);
		this.fileListSettingSplitPanel.setProportion(splitPanelRatio);
		final Dimension dialogSize =
				Optional.ofNullable(history.getUi()).map(UISizes::getExportDialog).orElse(Constants.settingDialogSize);
		this.setSize(dialogSize.width, dialogSize.height);
		this.templateHandler.initUI(history, template);
	}

	protected void updateTemplateUiState() {
	}

	public JBSplitter getFileListSettingSplitPanel() {
		return fileListSettingSplitPanel;
	}

	protected void createFileListTree() {
		//remove old component
		if (this.fileListLabel != null) {
			fileListPanel.remove(fileListLabel);
		}
		if (this.fileListDialog != null) {
			fileListPanel.remove(this.fileListDialog.getCenterPanel());
			disposeFileListDialog();
		}
		//create new components
		this.fileListDialog = createFilesDialog();
		final JComponent selectTreePanel = fileListDialog.getCenterPanel();
		final JPanel separatorPanel = UIFactory.createTitledSeparatorPanel(Constants.titleFileList, selectTreePanel);
		this.fileListLabel = simplePanel(separatorPanel).withBorder(createEmptyBorder());
		this.fileListPanel.add(fileListLabel, BorderLayout.NORTH);
		this.fileListPanel.add(selectTreePanel, BorderLayout.CENTER);
	}

	@NotNull
	private FileListDialog createFilesDialog() {
		final var allVfs = getListFilesInBgt();
		final FileListDialog dialog = new FileListDialog(this.project, List.copyOf(allVfs), null, null, true, false);
		dialog.addFileTreeChangeListener((d, e) -> {
			final Collection<VirtualFile> files = d.getSelectedFiles();
			this.buttonOK.setEnabled(null != files && !files.isEmpty());
		});
		return dialog;
	}

	private Set<VirtualFile> getListFilesInBgt() {
		return runInBgtWithReadLockAndWait(this::getListFiles, project);
	}

	private Set<VirtualFile> getListFiles() {
		final Set<VirtualFile> allVfs = new HashSet<>();
		for (VirtualFile virtualFile : Optional.ofNullable(this.selectedFiles).orElse(new VirtualFile[0])) {
			CommonUtils.collectExportFilesNest(project, allVfs, virtualFile);
		}
		return allVfs;
	}

	private void updateSettingPanelComponents() {
		createTemplatePanelTitledSeparator();
		createJarOutputPanelTiledSeparator();
		createOptionPanelTitledSeparator();
	}

	private void createTemplatePanelTitledSeparator() {
		// use enable checkout as title
//		createTitledSeparatorForPanel(this.templateTitlePanel, Constants.titleTemplateSetting, this.templateSelectComBox);
	}

	private void createJarOutputPanelTiledSeparator() {
		createTitledSeparatorForPanel(this.outputJarTitlePanel, Constants.titleJarFileSeparator, this.outPutJarFileComboBox);
	}

	private void createOptionPanelTitledSeparator() {
		createTitledSeparatorForPanel(this.optionTitlePanel, Constants.titleOptionSeparator, this.optionsPanel);
	}

	private void createTitledSeparatorForPanel(JPanel borderContainer, String title, JComponent separatorForComp) {
		final TitledSeparator separator = SeparatorFactory.createSeparator(title, separatorForComp);
		borderContainer.add(separator, BorderLayout.CENTER);
	}

	private void updateFileListSettingSplitPanel() {
		this.fileListSettingSplitPanel.setFirstComponent(this.fileListPanel);
		this.fileListSettingSplitPanel.setSecondComponent(this.settingPanel);
	}

	private void initMnemonics(){
		MnemonicHelper.init(getContentPane());
	}


	private void uiDebug() {
		debugButton.setVisible(true);
		this.fileListDialog.getFileList().addSelectionListener(() -> {
			System.out.println("in ui debug");
		});

	}

	private void migrateSavedHistory() {
		UpgradeManager.migrateHistoryToV2023(this.project);
	}

	public ExportOptions[] pickExportOptions() {
		final Component[] components = optionsPanel.getComponents();
		return Arrays.stream(components).filter(c -> c instanceof JCheckBox)
				.filter(c -> ((JCheckBox) c).isSelected())
				.map(Component::getName)
				.map(String::toLowerCase)
				.filter(n -> {
					try {
						ExportOptions.valueOf(n);
						return true;
					} catch (Exception e) {
						return false;
					}
				}).map(ExportOptions::valueOf).toArray(ExportOptions[]::new);
	}

		private void onSelectJarFileButton(ActionEvent event) {
		FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();
		Consumer<VirtualFile> chooserConsumer = new FileChooserConsumerImplForComboBox(this.outPutJarFileComboBox);
		FileChooser.chooseFile(descriptor, project, null, chooserConsumer);
	}


	private void onCancel() {
		this.dispose();
	}

	/**
	 * final selected files to export
	 *
	 * @return selected files, always not null, possible length=0
	 */
	public VirtualFile[] getExportingFiles() {
		return fileListDialog.getSelectedFiles().toArray(new VirtualFile[0]);
	}

	/**
	 * Retrieves the include and exclude selections from the file list dialog and returns an array of
	 * SettingSelectFile objects.
	 */
	public SettingSelectFile[] getIncludeExcludeSelections() {
		return this.fileListDialog.getStoreIncludeExcludeSelections();
	}

	public void onOK() {
		final VirtualFile[] finalSelectFiles = this.getExportingFiles();
		// move export action to BGT, avoid throw SLOW warning exception
		// read more: https://plugins.jetbrains.com/docs/intellij/threading-model.html
		backgroundRunWithoutLock(() -> doExport(finalSelectFiles), project, "Exporting files");
	}

	private void onDebug() {
	}

	public void setSelectedFiles(VirtualFile[] selectedFiles) {
		this.selectedFiles = selectedFiles;
		createFileListTree();
	}

	public void setIncludeExcludeSelections(SettingSelectFile[] inExSelectFiles) {
		this.selectedFiles =
				Arrays.stream(inExSelectFiles != null ? inExSelectFiles : new SettingSelectFile[0]).map(SettingSelectFile::getVirtualFile).toArray(VirtualFile[]::new);
		createFileListTree();
		this.fileListDialog.setFlaggedIncludeExcludeSelections(inExSelectFiles);
		this.fileListDialog.getHandler().setShouldUpdateIncludeExclude(true);
	}

	public FileListDialog getFileListDialog() {
		return fileListDialog;
	}

	public void doExport(VirtualFile[] exportFiles) {
		if (isEmpty(exportFiles)) {
			return;
		}
		final var app = ApplicationManager.getApplication();
		final var modules = runInBgtWithReadLockAndWait(() -> findModule(project, exportFiles), project);
		final String selectedOutputJarFullPath = (String) this.outPutJarFileComboBox.getModel().getSelectedItem();
		if (selectedOutputJarFullPath == null || selectedOutputJarFullPath.trim().isEmpty()) {
			app.invokeAndWait(() -> showErrorDialog(project, "The selected output path should not empty",
					Constants.actionName));
			return;
		}
		Path exportJarFullPath = Paths.get(selectedOutputJarFullPath.trim());
		if (!Files.isDirectory(exportJarFullPath)) {
			Path exportJarParentPath = exportJarFullPath.getParent();
			if (exportJarParentPath == null) {// when input file without parent dir, current dir as parent dir.
				String basePath = project.getBasePath();
				exportJarParentPath = Paths.get(Objects.requireNonNullElse(basePath, "./"));
				exportJarFullPath = exportJarParentPath.resolve(exportJarFullPath);
			}
			try {
				Files.createDirectories(exportJarParentPath);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			if (!Files.exists(exportJarParentPath)) {
				app.invokeAndWait(() -> showErrorDialog(project, "The selected output path is not exists",
						Constants.actionName));
			} else {
				String exportJarName = exportJarFullPath.getFileName().toString();
				if (!exportJarName.endsWith(".jar")) {
					exportJarFullPath = Paths.get(exportJarFullPath + ".jar");
				}
				if (Files.exists(exportJarFullPath)) {
					final int[] result = new int[1];
					final Path finalJarPath = exportJarFullPath;
					app.invokeAndWait(() -> result[0] = Messages.showYesNoDialog(project, finalJarPath + " already " +
									"exists, replace it? ",
							Constants.actionName, getWarningIcon()));
					if (result[0] == Messages.NO) {
						return;
					}
				}
				this.dispose();
				templateHandler.saveCurTemplate();
				templateHandler.saveGlobalTemplate();
				final CompileStatusNotification packager = new ExportPacker(project, exportFiles, exportJarFullPath,
						pickExportOptions());
				app.invokeAndWait(() -> CompilerManager.getInstance(project).make(project, null == modules ?
						new Module[0] : modules, packager));
			}
		} else {
			app.invokeAndWait(() -> showErrorDialog(project, "Please specify export jar file name",
					Constants.actionName));
		}
	}

	private boolean isEmpty(VirtualFile[] exportFiles) {
		if (exportFiles == null || exportFiles.length == 0) {
			ApplicationManager.getApplication().invokeAndWait(() -> showErrorDialog(project, "Export files is empty," +
					" " +
					"please select them first", Constants.actionName));
			return true;
		}
		return false;
	}

	@Override
	protected @Nullable JComponent createCenterPanel() {
		return null;//will use this contentPane replace super contentPane, return null here
	}

	@Override
	public void dispose() {
		disposeInEdt();
	}

	private void disposeInEdt() {
		ApplicationManager.getApplication().invokeAndWait(() -> {
			disposeFileListDialog();
			super.dispose();
		});
	}

	/**
	 * collection current ui components size
	 *
	 * @return sizes
	 */
	public UISizes currentUISizes() {
		final UISizes sizes = new UISizes();
		sizes.setExportDialog(this.getSize());
		sizes.setFileSettingSplitPanel(this.fileListSettingSplitPanel.getPreferredSize());
		sizes.setFileSettingSplitRatio(this.fileListSettingSplitPanel.getProportion());
		return sizes;
	}

	private void disposeFileListDialog() {
		if (this.fileListDialog == null || this.fileListDialog.isDisposed()) {
			return;
		}
		ApplicationManager.getApplication().invokeLater(() -> this.fileListDialog.disposeIfNeeded());
	}

	private static class FileChooserConsumerImplForComboBox implements Consumer<VirtualFile> {
		private final JComboBox<String> comboBox;

		public FileChooserConsumerImplForComboBox(JComboBox<String> comboBox) {
			this.comboBox = comboBox;
		}

		@Override
		public void consume(VirtualFile virtualFile) {
			String filePath = virtualFile.getPath();
			if (filePath.trim().isEmpty()) {
				return;
			}
			((DefaultComboBoxModel<String>) comboBox.getModel()).insertElementAt(filePath, 0);
			comboBox.setSelectedIndex(0);
		}
	}

}
