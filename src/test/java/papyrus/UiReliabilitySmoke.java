package papyrus;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.stage.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exercises responsive layout, a larger gallery, editing, and report layout on a desktop. */
public final class UiReliabilitySmoke {
    static PapyrusApp app; static Stage stage; static Dialog<ButtonType> editor; static DialogPane toolPane;
    static final ArrayDeque<Runnable> steps = new ArrayDeque<>();
    public static void main(String[] args) throws Exception {
        UiTestSupport.start();
        Path data=Files.createTempDirectory("papyrus-ui-reliability-");System.setProperty("papyrus.dataDir",data.toString());
        try(Store store=new Store(data)) {
            store.setup("review","Review user","review-password");store.login("review","review-password");
            store.save(Store.Entity.CATEGORIES,null,Map.of("cat_libelle","Category"));store.save(Store.Entity.TYPES,null,Map.of("ty_libelle","Book"));
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("test1.db"));Statement sql=c.createStatement()) {
                sql.execute("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<600) INSERT INTO docs(doc_libelle,author,copy_code,cat_id,type_id) SELECT 'Book '||x,'Author '||x,'COPY-'||x,1,1 FROM n");
            }
        }
        Platform.startup(()->{
            try { app=new PapyrusApp();stage=new Stage();app.start(stage);app.store.login("review","review-password");app.showWorkspace();stage.setWidth(960);stage.setHeight(680);
                for(String lang:List.of("en","fr","ar")) {
                    steps.add(()->{app.language(lang);app.navigate("books");});
                    await(()->gallery()!=null&&!gallery().getItems().isEmpty(),"Catalog loaded");
                    steps.add(()->{
                        var root=stage.getScene().getRoot();root.applyCss();root.layout();
                        var logout=root.lookup("#logout").localToScene(root.lookup("#logout").getBoundsInLocal());
                        check(logout.getMinY()>=0&&logout.getMaxY()<=stage.getScene().getHeight(),"Sign out stays visible at minimum window size: "+lang);
                        check(root.lookupAll(".book-card").size()<40,"Gallery constructs visible cards only");
                        ((TextField)root.lookup("#search")).setText("COPY-042");
                    });
                    await(()->gallery().getItems().isEmpty(),"Unmatched copy identifier gives no results");
                    steps.add(()->((TextField)stage.getScene().lookup("#search")).setText("COPY-600"));
                    await(()->gallery().getItems().stream().mapToInt(List::size).sum()==1,"Find copy by barcode");
                    steps.add(app::refresh);
                    await(()->gallery().getItems().stream().mapToInt(List::size).sum()==1,"Filter survives refresh");
                    steps.add(()->check(((TextField)stage.getScene().lookup("#search")).getText().equals("COPY-600"),"Search text retained"));
                }
                steps.add(()->{((TextField)stage.getScene().lookup("#search")).setText("COPY-599");app.refresh();});
                await(()->gallery().getItems().stream().mapToInt(List::size).sum()==1,"Search survives refresh before debounce finishes");
                steps.add(()->check(((TextField)stage.getScene().lookup("#search")).getText().equals("COPY-599"),"Latest keystroke retained"));
                steps.add(()->{try{editor=app.editor(Store.Entity.BOOKS,app.store.list(Store.Entity.BOOKS).getFirst());editor.show();}catch(Exception e){throw new RuntimeException(e);}});
                await(()->((ComboBox<?>)editor.getDialogPane().lookup("#field-cat_id")).getValue()!=null,"Editor choices loaded");
                steps.add(()->Platform.runLater(()->((Button)editor.getDialogPane().lookup("#quick-add-cat_id")).fire()));
                await(()->pane("field-cat_libelle")!=null,"Quick category dialog opened");
                steps.add(()->{DialogPane nested=pane("field-cat_libelle");((TextField)nested.lookup("#field-cat_libelle")).setText("Quick category");((Button)nested.lookupButton(nested.getButtonTypes().getFirst())).fire();});
                await(()->((ComboBox<?>)editor.getDialogPane().lookup("#field-cat_id")).getValue() instanceof Store.Row row && row.text("cat_libelle").equals("Quick category"),"Quick category selected in parent form");
                steps.add(()->{
                    ((TextField)editor.getDialogPane().lookup("#field-author")).setText("Updated author / مؤلف");
                    ((TextField)editor.getDialogPane().lookup("#field-isbn")).setText("978-1-2345-6789-0");
                    ((TextField)editor.getDialogPane().lookup("#field-shelf")).setText("A-12");
                    check(editor.getDialogPane().lookup("#quick-add-cat_id")!=null,"Quick category creation exists");
                    ((Button)editor.getDialogPane().lookupButton(editor.getDialogPane().getButtonTypes().getFirst())).fire();
                });
                await(()->!editor.isShowing(),"Metadata saved");
                steps.add(()->{try{var row=app.store.list(Store.Entity.BOOKS).getFirst();check(row.text("author").equals("Updated author / مؤلف")&&row.text("shelf").equals("A-12"),"Metadata persisted");}catch(Exception e){throw new RuntimeException(e);}});
                steps.add(()->{
                    try{int id=app.store.save(Store.Entity.STUDENTS,null,Map.of("cne","ARCHIVE-1","nom","N","prenom","P","phone","555"));app.store.delete(Store.Entity.STUDENTS,id);}catch(Exception e){throw new RuntimeException(e);}
                    Platform.runLater(()->new LibraryTools(app).archived(Store.Entity.STUDENTS));
                });
                await(()->pane("archived-records")!=null&&!((TableView<?>)pane("archived-records").lookup("#archived-records")).getItems().isEmpty(),"Archived student shown");
                steps.add(()->{toolPane=pane("archived-records");((TableView<?>)toolPane.lookup("#archived-records")).getSelectionModel().selectFirst();((Button)toolPane.lookup("#restore-record")).fire();});
                await(()->((TableView<?>)toolPane.lookup("#archived-records")).getItems().isEmpty(),"Archived student restored");
                steps.add(()->((Button)toolPane.lookupButton(toolPane.getButtonTypes().getFirst())).fire());
                steps.add(()->Platform.runLater(()->new LibraryTools(app).password()));
                await(()->pane("current-password")!=null,"Password dialog opened");
                steps.add(()->{toolPane=pane("current-password");((PasswordField)toolPane.lookup("#current-password")).setText("review-password");((PasswordField)toolPane.lookup("#new-password")).setText("changed-password");((PasswordField)toolPane.lookup("#confirm-new-password")).setText("changed-password");((Button)toolPane.lookupButton(toolPane.getButtonTypes().getFirst())).fire();});
                await(()->pane("current-password")==null,"Password form saved");
                steps.add(()->{
                    for(Window window:List.copyOf(Window.getWindows())) if(window!=stage&&window.isShowing()&&window.getScene()!=null&&window.getScene().getRoot() instanceof DialogPane pane) ((Button)pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                });
                steps.add(()->{
                    var record=new Store.Row(Map.of("doc_libelle","تقرير المكتبة","student","طالب","cne","ST-1","phone","123","return_d","2026-01-01"));
                    VBox report=new LibraryTools(app).reportPage(Collections.nCopies(10,record),1,1,480);report.resize(480,720);report.applyCss();report.layout();
                    check(report.getChildren().getLast().getBoundsInParent().getMaxY()<=720,"Printed report fits its page");
                    System.out.println("UI RELIABILITY PASSED: minimum window size in 3 languages, virtual gallery, barcode search, retained filters, metadata and quick category forms, archive restore, password change, printable report");
                    try{app.stop();}catch(Exception e){throw new RuntimeException(e);}stage.close();Platform.exit();
                });
                advance();
            } catch(Throwable e){e.printStackTrace();System.exit(1);}
        });
    }
    static DialogPane pane(String id){
        for(Window window:Window.getWindows())if(window.isShowing()&&window.getScene()!=null&&window.getScene().lookup("#"+id)!=null){
            javafx.scene.Node node=window.getScene().lookup("#"+id);while(node!=null){if(node instanceof DialogPane pane)return pane;node=node.getParent();}
        }return null;
    }
    static BookGallery gallery(){return (BookGallery)stage.getScene().lookup("#book-gallery");}
    static void await(BooleanSupplier condition,String message) {
        steps.add(new Runnable(){long deadline;public void run(){if(deadline==0)deadline=System.nanoTime()+15_000_000_000L;UiTestSupport.step=message;if(condition.getAsBoolean()){UiTestSupport.progress(message);return;}check(System.nanoTime()<deadline,message);steps.addFirst(this);}});
    }
    static void advance(){try{if(steps.isEmpty())return;steps.remove().run();PauseTransition wait=new PauseTransition(Duration.millis(200));wait.setOnFinished(e->advance());wait.play();}catch(Throwable e){e.printStackTrace();System.exit(1);}}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
