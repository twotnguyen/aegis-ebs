package aegis.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Toolkit;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;

/**
 * AEGIS desktop device client — SIMULATION / TRAINING ONLY.
 *
 * <p>Thin JavaFX shell over {@link AlertClientEngine}: permanent SIMULATION banner,
 * connection status, durable-inbox alert table (survives restart), warning popup +
 * optional beep on fresh alerts, and a separate human "Acknowledge" gesture that
 * sends ACK_USER (never implied by ACK_RECEIVED).
 *
 * <p>Run: {@code mvn -pl aegis-client javafx:run} (plugin builds the module-path),
 * with flags e.g. {@code -Djavafx.args="--host localhost --port 9443 --device-id DEV-B01-F05-01
 * --truststore truststore.p12 --truststore-password changeit --inbox client-inbox.db --sound"}.
 */
public class ClientApp extends Application {

  /** Table row wrapper so ACK state updates without rebuilding the record. */
  static final class AlertRow {
    final StringProperty alertId = new SimpleStringProperty();
    final StringProperty revision = new SimpleStringProperty();
    final StringProperty title = new SimpleStringProperty();
    final StringProperty priority = new SimpleStringProperty();
    final StringProperty category = new SimpleStringProperty();
    final StringProperty receivedAt = new SimpleStringProperty();
    final BooleanProperty userAcked = new SimpleBooleanProperty(false);
    final StringProperty simulation = new SimpleStringProperty("SIMULATION");

    AlertRow(AlertRecord a, boolean acked) {
      alertId.set(a.alertId());
      revision.set(Long.toString(a.revision()));
      title.set(a.title());
      priority.set(a.priority() == null ? "INFO" : a.priority());
      category.set(a.category() == null ? "—" : a.category());
      receivedAt.set(a.receivedAt() == null ? "" : a.receivedAt());
      userAcked.set(acked);
      simulation.set(a.simulation() ? "SIMULATION" : "SIMULATION?");
    }
  }

  private ClientConfig cfg;
  private InboxStore inbox;
  private AlertClientEngine engine;
  private final ObservableList<AlertRow> rows = FXCollections.observableArrayList();
  private Label statusLabel;
  private CheckBox soundBox;
  private TableView<AlertRow> table;

  @Override
  public void start(Stage stage) {
    List<String> raw = getParameters().getRaw();
    cfg = ClientConfig.fromArgs(raw.toArray(new String[0]));
    System.out.println("AEGIS client (SIMULATION/TRAINING) device=" + cfg.deviceId()
        + " target=" + cfg.host() + ":" + cfg.port() + " inbox=" + cfg.inboxDb());
    inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    engine = new AlertClientEngine(cfg, inbox,
        (host, port) -> TlsSupport.clientContext(cfg.truststore(), cfg.truststorePassword())
            .getSocketFactory().createSocket(host, port),
        Thread::sleep, Clock.systemUTC(), new Random(), new EngineListener());

    // Permanent simulation banner — this system must never be mistaken for a real EBS.
    Label banner = new Label("⚠ SIMULATION / TRAINING ONLY — NOT A REAL EMERGENCY BROADCAST SYSTEM ⚠");
    banner.setStyle("-fx-background-color:#b71c1c;-fx-text-fill:white;-fx-font-weight:bold;-fx-padding:8 12 8 12;");
    banner.setMaxWidth(Double.MAX_VALUE);

    statusLabel = new Label("State: NEW — " + cfg.deviceId() + " → " + cfg.host() + ":" + cfg.port());
    statusLabel.setStyle("-fx-padding:6 12 6 12;-fx-font-size:13;");

    table = new TableView<>(rows);
    table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    table.setPlaceholder(new Label("No active alerts. Durable inbox: " + cfg.inboxDb()));
    table.getColumns().setAll(List.of(
        col("Alert", 140, r -> r.alertId),
        col("Rev", 50, r -> r.revision),
        col("Priority", 90, r -> r.priority),
        col("Category", 90, r -> r.category),
        col("Title", 320, r -> r.title),
        col("Received (UTC)", 180, r -> r.receivedAt),
        col("Label", 100, r -> r.simulation),
        ackCol()));
    VBox.setVgrow(table, Priority.ALWAYS);

    Button ackButton = new Button("Acknowledge (user gesture → ACK_USER)");
    ackButton.setOnAction(ev -> {
      AlertRow row = table.getSelectionModel().getSelectedItem();
      if (row == null) return;
      boolean ok = engine.ackUser(row.alertId.get(), cfg.userId());
      if (ok) row.userAcked.set(true);
      else table.refresh();
    });
    Button syncButton = new Button("Sync now");
    syncButton.setOnAction(ev -> engine.requestSync());
    soundBox = new CheckBox("Alert sound");
    soundBox.setSelected(cfg.soundEnabled());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox buttons = new HBox(10, ackButton, syncButton, spacer, soundBox);
    buttons.setPadding(new Insets(8, 12, 8, 12));

    VBox center = new VBox(table);
    BorderPane root = new BorderPane();
    VBox top = new VBox(banner, statusLabel);
    root.setTop(top);
    root.setCenter(center);
    root.setBottom(buttons);

    // Durable redisplay: whatever was committed in a previous session shows up immediately.
    reloadFromInbox();

    stage.setTitle("AEGIS Device Client — SIMULATION — " + cfg.deviceId());
    stage.setScene(new Scene(root, 1024, 640));
    stage.setOnCloseRequest(ev -> shutdown());
    stage.show();

    engine.start();
  }

  @Override
  public void stop() {
    shutdown();
  }

  private void shutdown() {
    if (engine != null) engine.close();
    if (inbox != null) inbox.close();
  }

  private void reloadFromInbox() {
    rows.clear();
    for (AlertRecord a : inbox.activeAlerts()) {
      rows.add(new AlertRow(a, false));
    }
  }

  private static TableColumn<AlertRow, String> col(String name, double width,
                                                   java.util.function.Function<AlertRow, StringProperty> f) {
    TableColumn<AlertRow, String> c = new TableColumn<>(name);
    c.setPrefWidth(width);
    c.setCellValueFactory(data -> f.apply(data.getValue()));
    return c;
  }

  private static TableColumn<AlertRow, Boolean> ackCol() {
    TableColumn<AlertRow, Boolean> c = new TableColumn<>("User ACK");
    c.setPrefWidth(90);
    c.setCellValueFactory(data -> data.getValue().userAcked);
    return c;
  }

  /** Engine callbacks arrive on background threads — marshal everything to FX. */
  private final class EngineListener implements ClientListener {
    @Override
    public void onState(AlertClientEngine.State oldState, AlertClientEngine.State newState) {
      System.out.println("[client] state " + oldState + " -> " + newState);
      Platform.runLater(() -> {
        statusLabel.setText("State: " + newState + " — " + cfg.deviceId() + " → " + cfg.host() + ":" + cfg.port());
        statusLabel.setStyle("-fx-padding:6 12 6 12;-fx-font-size:13;-fx-text-fill:"
            + switch (newState) {
              case ONLINE -> "#1b5e20";
              case RECONNECTING, CONNECTING -> "#e65100";
              case FAILED, STOPPED -> "#b71c1c";
              default -> "#37474f";
            } + ";");
        if (newState == AlertClientEngine.State.ONLINE) reloadFromInbox();
      });
    }

    @Override
    public void onAlert(AlertRecord alert, boolean fresh) {
      if (!fresh) return; // duplicates/stale never redisplay
      Platform.runLater(() -> {
        rows.removeIf(r -> r.alertId.get().equals(alert.alertId()));
        rows.add(0, new AlertRow(alert, false));
        if (soundBox.isSelected()) {
          try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) { }
        }
        Alert popup = new Alert(Alert.AlertType.WARNING,
            (alert.instruction() == null ? "" : alert.instruction() + "\n\n")
                + "Alert: " + alert.alertId() + " rev " + alert.revision()
                + " | Priority: " + alert.priority() + " | Category: " + alert.category()
                + "\nExpires: " + (alert.expiresAt() == null ? "—" : alert.expiresAt())
                + "\n\nThis is a SIMULATION. Press Acknowledge to record a user acknowledgement.",
            ButtonType.OK);
        popup.setTitle("SIMULATION — Emergency Alert");
        popup.setHeaderText("⚠ SIMULATION / TRAINING — " + alert.title());
        popup.show();
      });
    }

    @Override
    public void onCancel(String alertId, long revision, String reason) {
      Platform.runLater(() -> rows.removeIf(r -> r.alertId.get().equals(alertId)));
    }

    @Override
    public void onError(String context, Throwable error) {
      Platform.runLater(() -> statusLabel.setText("State: " + engine.state() + " — " + context
          + (error == null || error.getMessage() == null ? "" : ": " + error.getMessage())));
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
