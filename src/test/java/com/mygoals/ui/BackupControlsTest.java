package com.mygoals.ui;

import com.mygoals.domain.ProfileState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupControlsTest
{
    private static final class Dialogs implements BackupControls.Dialogs
    {
        Filepath selection = Filepath.Unchecked.getRooted(Path.of(System.getProperty("java.io.tmpdir"), "synthetic-my-goals.zip"));
        boolean approved = true; int confirmations; Runnable duringConfirm = () -> {};
        final List<BackupResult> results = new ArrayList<>();
        public Filepath choose(boolean save) { return selection; }
        public boolean confirm(BackupResult preview, String name) { confirmations++; duringConfirm.run(); return approved; }
        public void result(BackupResult result) { results.add(result); }
    }
    private static final class Backend implements BackupControls.Backend
    {
        final List<String> calls = new ArrayList<>(); Consumer<BackupResult> completed;
        public void export(String token, Filepath file, Consumer<BackupResult> completed) { calls.add("export:" + token); this.completed = completed; }
        public void prepare(String token, Filepath file, Consumer<BackupResult> completed) { calls.add("prepare:" + token); this.completed = completed; }
        public void restore(String token, String id, Consumer<BackupResult> completed) { calls.add("restore:" + id); this.completed = completed; }
        public void cancel(String token, String id) { calls.add("cancel:" + id); }
    }
    private GoalsView view(String token, boolean editable)
    { return new GoalsView(token, "Character\nSaved locally", "", editable, new ProfileState("a".repeat(64)), Instant.parse("2026-10-16T17:00:00Z")); }
    private JMenuItem button(BackupControls panel, String name) { return name.equals("exportBackup") ? panel.exportItem() : panel.restoreItem(); }

    @Test public void exportRequiresAnEditableCharacterAndDisablesRepeatedClicksUntilItFinishes() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Dialogs dialogs = new Dialogs(); Backend backend = new Backend(); BackupControls controls = new BackupControls(dialogs, () -> {}); controls.setBackend(backend);
            controls.render(view("a", false)); assertFalse(button(controls, "exportBackup").isEnabled());
            controls.render(view("a", true)); button(controls, "exportBackup").doClick();
            assertFalse(button(controls, "exportBackup").isEnabled()); assertFalse(button(controls, "restoreBackup").isEnabled());
            backend.completed.accept(BackupResult.success("Exported"));
            assertEquals(List.of("export:a"), backend.calls); assertEquals(1, dialogs.results.size()); assertTrue(button(controls, "restoreBackup").isEnabled());
        });
    }

    @Test public void restoreRequiresAValidatedPreviewAndExplicitConfirmation() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Dialogs dialogs = new Dialogs(); Backend backend = new Backend(); List<String> actions = new ArrayList<>();
            BackupControls controls = new BackupControls(dialogs, () -> actions.add("close details")); controls.setBackend(backend); controls.render(view("a", true));
            button(controls, "restoreBackup").doClick(); assertEquals(0, dialogs.confirmations);
            backend.completed.accept(BackupResult.preview("approved", 3, 50, 0));
            assertEquals(1, dialogs.confirmations); assertEquals(List.of("prepare:a", "restore:approved"), backend.calls);
            assertEquals(List.of("close details"), actions);
            backend.completed.accept(BackupResult.success("Restored")); assertEquals(1, dialogs.results.size());
        });
    }

    @Test public void cancelAndInvalidFilesDoNotReachRestore() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Dialogs dialogs = new Dialogs(); dialogs.approved = false; Backend backend = new Backend(); BackupControls controls = new BackupControls(dialogs, () -> {});
            controls.setBackend(backend); controls.render(view("a", true)); button(controls, "restoreBackup").doClick();
            backend.completed.accept(BackupResult.preview("cancelled", 3, 50, 0)); assertEquals(List.of("prepare:a", "cancel:cancelled"), backend.calls);
            button(controls, "restoreBackup").doClick(); backend.completed.accept(BackupResult.failure("Invalid backup"));
            assertEquals(1, dialogs.confirmations); assertEquals(1, dialogs.results.size()); assertTrue(button(controls, "restoreBackup").isEnabled());
        });
    }

    @Test public void accountChangesInvalidatePendingPreviewsAndConfirmations() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Dialogs dialogs = new Dialogs(); Backend backend = new Backend(); BackupControls controls = new BackupControls(dialogs, () -> {});
            controls.setBackend(backend); controls.render(view("a", true)); button(controls, "restoreBackup").doClick();
            controls.render(view("b", true)); backend.completed.accept(BackupResult.preview("stale", 1, 1, 0));
            assertEquals(0, dialogs.confirmations); assertTrue(backend.calls.contains("cancel:stale"));
            button(controls, "restoreBackup").doClick(); dialogs.duringConfirm = () -> controls.render(view("c", true));
            backend.completed.accept(BackupResult.preview("changed-in-dialog", 1, 1, 0));
            assertFalse(backend.calls.stream().anyMatch(c -> c.startsWith("restore:")));
        });
    }

    @Test public void cancelFilePickerMakesNoBackendCall() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            Dialogs dialogs = new Dialogs(); dialogs.selection = null; Backend backend = new Backend(); BackupControls controls = new BackupControls(dialogs, () -> {});
            controls.setBackend(backend); controls.render(view("a", true)); button(controls, "exportBackup").doClick(); button(controls, "restoreBackup").doClick();
            assertTrue(backend.calls.isEmpty()); assertTrue(button(controls, "exportBackup").isEnabled());
        });
    }
}
