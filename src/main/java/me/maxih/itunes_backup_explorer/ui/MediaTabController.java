package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.api.BackupFile;
import me.maxih.itunes_backup_explorer.api.BackupReadException;
import me.maxih.itunes_backup_explorer.api.ITunesBackup;
import me.maxih.itunes_backup_explorer.api.NotUnlockedException;
import me.maxih.itunes_backup_explorer.api.UnsupportedCryptoException;
import me.maxih.itunes_backup_explorer.util.FileSize;
import me.maxih.itunes_backup_explorer.util.MediaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MediaTabController {
    private static final Logger logger = LoggerFactory.getLogger(MediaTabController.class);

    private static final int PAGE_SIZE = 100;
    private static final int THUMB_SIZE = 90;

    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "heic", "heif", "gif", "bmp", "tiff"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mov", "mp4", "m4v", "avi"
    );

    private ITunesBackup selectedBackup;
    private List<BackupFile> allMedia = new ArrayList<>();
    private List<BackupFile> filteredMedia = new ArrayList<>();
    private int currentPage = 0;
    private BackupFile selectedFile;
    private VBox selectedTile;

    @FXML FlowPane mediaGrid;
    @FXML ScrollPane gridScrollPane;
    @FXML ImageView previewImage;
    @FXML Label fileNameLabel;
    @FXML Label fileSizeLabel;
    @FXML Label fileDateLabel;
    @FXML Label fileDomainLabel;
    @FXML Label filePathLabel;
    @FXML Button prevPageBtn;
    @FXML Button nextPageBtn;
    @FXML Button saveBtn;
    @FXML Button openBtn;
    @FXML Button exportAllBtn;
    @FXML Label pageInfoLabel;
    @FXML Label resultsCountLabel;
    @FXML ToggleGroup filterGroup;
    @FXML ToggleButton filterAll;
    @FXML ToggleButton filterPhotos;
    @FXML ToggleButton filterVideos;
    @FXML SplitPane splitPane;
    @FXML StackPane previewContainer;

    @FXML
    public void initialize() {
        filterGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                filterAll.setSelected(true);
                return;
            }
            applyFilter();
        });

        previewImage.fitWidthProperty().bind(
                previewContainer.widthProperty().subtract(28)
        );
        previewImage.fitHeightProperty().bind(
                previewContainer.heightProperty().subtract(28)
        );

        gridScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double width = newBounds.getWidth();
            if (width > 0) {
                mediaGrid.setPrefWrapLength(width);
                mediaGrid.requestLayout();
            }
        });

        Platform.runLater(() -> {
            if (splitPane.getDividers().size() > 0) {
                splitPane.getDividers().get(0).positionProperty().addListener((obs, oldPos, newPos) -> {
                    double width = gridScrollPane.getViewportBounds().getWidth();
                    if (width > 0) {
                        mediaGrid.setPrefWrapLength(width);
                        mediaGrid.requestLayout();
                    }
                });
            }
        });

        Thread toolsCheck = new Thread(MediaConverter::detectTools, "media-tools-check");
        toolsCheck.setDaemon(true);
        toolsCheck.start();
    }

    public void tabShown(ITunesBackup backup) {
        Platform.runLater(() -> {
            double width = gridScrollPane.getViewportBounds().getWidth();
            if (width > 0) {
                mediaGrid.setPrefWrapLength(width);
                mediaGrid.requestLayout();
            }
        });

        if (backup == this.selectedBackup && !this.allMedia.isEmpty()) return;

        this.selectedBackup = backup;
        this.allMedia.clear();
        this.filteredMedia.clear();
        this.selectedFile = null;
        this.selectedTile = null;
        clearPreview();
        ThumbnailService.getInstance().clearCache();

        javafx.concurrent.Task<List<BackupFile>> task = new javafx.concurrent.Task<>() {
            @Override
            protected List<BackupFile> call() throws Exception {
                List<String> photoPatterns = Arrays.asList(
                        "%.jpg", "%.jpeg", "%.png", "%.heic", "%.heif", "%.gif", "%.bmp", "%.tiff"
                );
                List<String> videoPatterns = Arrays.asList(
                        "%.mov", "%.mp4", "%.m4v", "%.avi"
                );

                List<String> allPatterns = new ArrayList<>(photoPatterns);
                allPatterns.addAll(videoPatterns);

                List<BackupFile> cameraRoll = backup.searchFilesMultiPattern("CameraRollDomain", allPatterns);
                List<BackupFile> mediaDomain = backup.searchFilesMultiPattern("MediaDomain", allPatterns);

                List<BackupFile> combined = new ArrayList<>(cameraRoll);
                combined.addAll(mediaDomain);

                return combined.stream()
                        .filter(f -> f.getFileType() == BackupFile.FileType.FILE)
                        .collect(Collectors.toList());
            }
        };

        task.setOnSucceeded(event -> {
            allMedia = task.getValue();
            applyFilter();
        });

        task.setOnFailed(event -> {
            logger.error("Failed to load media", task.getException());
            Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to load media: " + task.getException().getMessage());
        });

        new Thread(task).start();
    }

    private void applyFilter() {
        Toggle selected = filterGroup.getSelectedToggle();

        if (selected == filterPhotos) {
            filteredMedia = allMedia.stream()
                    .filter(f -> isPhoto(f.getFileExtension()))
                    .collect(Collectors.toList());
        } else if (selected == filterVideos) {
            filteredMedia = allMedia.stream()
                    .filter(f -> isVideo(f.getFileExtension()))
                    .collect(Collectors.toList());
        } else {
            filteredMedia = new ArrayList<>(allMedia);
        }

        resultsCountLabel.setText(filteredMedia.size() + " items");
        currentPage = 0;
        selectedFile = null;
        selectedTile = null;
        clearPreview();
        showPage(currentPage);
    }

    private void showPage(int page) {
        mediaGrid.getChildren().clear();
        gridScrollPane.setVvalue(0);

        int totalPages = Math.max(1, (int) Math.ceil((double) filteredMedia.size() / PAGE_SIZE));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        currentPage = page;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filteredMedia.size());

        pageInfoLabel.setText("Page " + (page + 1) + " of " + totalPages);
        prevPageBtn.setDisable(page == 0);
        nextPageBtn.setDisable(page >= totalPages - 1);

        if (filteredMedia.isEmpty()) return;

        List<BackupFile> pageItems = filteredMedia.subList(start, end);

        for (BackupFile file : pageItems) {
            VBox tile = createTile(file);
            mediaGrid.getChildren().add(tile);
        }

        Platform.runLater(() -> mediaGrid.requestLayout());
    }

    private VBox createTile(BackupFile file) {
        ImageView thumbView = new ImageView();
        thumbView.setFitWidth(THUMB_SIZE);
        thumbView.setFitHeight(THUMB_SIZE);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);

        String ext = file.getFileExtension();
        if (ThumbnailService.isVideo(ext)) {
            thumbView.setImage(ThumbnailService.getVideoPlaceholder());
        } else {
            thumbView.setImage(ThumbnailService.getPhotoPlaceholder());
        }

        ThumbnailService.getInstance().loadThumbnail(file, THUMB_SIZE, thumbView::setImage);

        StackPane thumbContainer = new StackPane(thumbView);
        thumbContainer.setPrefSize(THUMB_SIZE, THUMB_SIZE);
        thumbContainer.setMinSize(THUMB_SIZE, THUMB_SIZE);
        thumbContainer.setMaxSize(THUMB_SIZE, THUMB_SIZE);
        thumbContainer.getStyleClass().add("media-thumbnail");

        Label nameLabel = new Label(file.getFileName());
        nameLabel.getStyleClass().add("media-label");
        nameLabel.setMaxWidth(THUMB_SIZE + 10);
        nameLabel.setAlignment(Pos.CENTER);

        VBox tile = new VBox(4, thumbContainer, nameLabel);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().add("media-tile");
        tile.setPrefWidth(THUMB_SIZE + 20);
        tile.setMaxWidth(THUMB_SIZE + 20);

        tile.setOnMouseClicked(event -> selectMedia(file, tile));

        return tile;
    }

    private void selectMedia(BackupFile file, VBox tile) {
        if (selectedTile != null) {
            selectedTile.getStyleClass().remove("media-tile-selected");
        }

        selectedFile = file;
        selectedTile = tile;
        tile.getStyleClass().add("media-tile-selected");

        saveBtn.setDisable(false);
        openBtn.setDisable(false);

        fileNameLabel.setText(file.getFileName());
        fileSizeLabel.setText("Size: " + FileSize.format(file.getSize()));
        fileDomainLabel.setText("Domain: " + file.domain);
        filePathLabel.setText("Path: " + file.relativePath);
        fileDateLabel.setText("");

        int maxSize = (int) Math.max(800, previewContainer.getWidth());
        ThumbnailService.getInstance().loadPreview(file, maxSize, previewImage::setImage);
    }

    private void clearPreview() {
        previewImage.setImage(null);
        fileNameLabel.setText("");
        fileSizeLabel.setText("");
        fileDateLabel.setText("");
        fileDomainLabel.setText("");
        filePathLabel.setText("");
        saveBtn.setDisable(true);
        openBtn.setDisable(true);
    }

    @FXML
    public void prevPage() {
        if (currentPage > 0) {
            showPage(currentPage - 1);
        }
    }

    @FXML
    public void nextPage() {
        int totalPages = (int) Math.ceil((double) filteredMedia.size() / PAGE_SIZE);
        if (currentPage < totalPages - 1) {
            showPage(currentPage + 1);
        }
    }

    @FXML
    public void saveFile() {
        if (selectedFile == null) return;

        BackupFile fileToSave = selectedFile;

        DirectoryChooser chooser = new DirectoryChooser();
        File lastDir = PreferencesController.getLastExportDirectory();
        if (lastDir != null && lastDir.exists()) chooser.setInitialDirectory(lastDir);
        File destination = chooser.showDialog(mediaGrid.getScene().getWindow());
        if (destination == null) return;

        PreferencesController.setLastExportDirectory(destination);

        try {
            fileToSave.extractToFolder(destination, false);
        } catch (FileAlreadyExistsException e) {
            Dialogs.showAlert(Alert.AlertType.WARNING, "File already exists: " + e.getMessage());
        } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
            logger.error("Failed to save file", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to save: " + e.getMessage());
        }
    }

    @FXML
    public void openFile() {
        if (selectedFile == null) return;

        BackupFile fileToOpen = selectedFile;

        javafx.concurrent.Task<File> task = new javafx.concurrent.Task<>() {
            @Override
            protected File call() throws Exception {
                String ext = fileToOpen.getFileExtension();
                File tempFile = Files.createTempFile("media_", ext != null ? "." + ext : "").toFile();
                tempFile.deleteOnExit();
                fileToOpen.extract(tempFile, false);
                return tempFile;
            }
        };

        task.setOnSucceeded(event -> {
            File fileResult = task.getValue();
            Thread opener = new Thread(() -> {
                try {
                    Desktop.getDesktop().open(fileResult);
                } catch (IOException e) {
                    logger.error("Failed to open file", e);
                    Platform.runLater(() ->
                            Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to open: " + e.getMessage()));
                }
            });
            opener.setDaemon(true);
            opener.start();
        });

        task.setOnFailed(event -> {
            logger.error("Failed to extract file", task.getException());
            Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to extract: " + task.getException().getMessage());
        });

        new Thread(task).start();
    }

    @FXML
    public void exportAll() {
        if (filteredMedia.isEmpty()) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File lastDir = PreferencesController.getLastExportDirectory();
        if (lastDir != null && lastDir.exists()) chooser.setInitialDirectory(lastDir);
        File destination = chooser.showDialog(mediaGrid.getScene().getWindow());
        if (destination == null) return;

        PreferencesController.setLastExportDirectory(destination);

        boolean withRelativePath = PreferencesController.getCreateDirectoryStructure();
        boolean preserveTimestamps = PreferencesController.getPreserveTimestamps();
        boolean skipExisting = PreferencesController.getSkipExistingFiles();

        javafx.concurrent.Task<int[]> task = new javafx.concurrent.Task<>() {
            @Override
            protected int[] call() {
                int success = 0;
                int failed = 0;
                for (BackupFile file : filteredMedia) {
                    try {
                        file.extractToFolder(destination, withRelativePath, preserveTimestamps);
                        success++;
                    } catch (FileAlreadyExistsException e) {
                        if (!skipExisting) failed++;
                    } catch (Exception e) {
                        logger.error("Failed to export {}", file.relativePath, e);
                        failed++;
                    }
                }
                return new int[]{success, failed};
            }
        };

        task.setOnSucceeded(event -> {
            int[] result = task.getValue();
            Dialogs.showAlert(Alert.AlertType.INFORMATION,
                    "Export complete: " + result[0] + " files exported" +
                            (result[1] > 0 ? ", " + result[1] + " failures" : ""));
        });

        task.setOnFailed(event -> {
            logger.error("Failed to export media", task.getException());
            Dialogs.showAlert(Alert.AlertType.ERROR, "Export failed: " + task.getException().getMessage());
        });

        new Thread(task).start();
    }

    private static boolean isPhoto(String extension) {
        return extension != null && PHOTO_EXTENSIONS.contains(extension.toLowerCase());
    }

    private static boolean isVideo(String extension) {
        return extension != null && VIDEO_EXTENSIONS.contains(extension.toLowerCase());
    }
}
