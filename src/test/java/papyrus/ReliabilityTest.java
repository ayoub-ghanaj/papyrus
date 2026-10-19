package papyrus;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ReliabilityTest {
    @TempDir Path directory;
    Store owner;
    @BeforeEach void setup() throws Exception {
        owner = new Store(directory.resolve("library")); owner.setup("owner", "Owner", "review-password"); owner.login("owner", "review-password");
    }
    @AfterEach void close() throws Exception { owner.close(); }
    int staff() throws Exception { return owner.save(Store.Entity.STAFF, null, Map.of("cin","second","nom","Other","prenom","Admin","role","1","pass","review-password")); }
    int student() throws Exception { return owner.save(Store.Entity.STUDENTS, null, Map.of("cne","S1","nom","N","prenom","P","phone","123")); }
    int book() throws Exception {
        int cat=owner.save(Store.Entity.CATEGORIES,null,Map.of("cat_libelle","Review"));
        int type=owner.save(Store.Entity.TYPES,null,Map.of("ty_libelle","Book"));
        return owner.save(Store.Entity.BOOKS,null,Map.of("doc_libelle","Book","cat_id",""+cat,"type_id",""+type));
    }
    @Test void roleChangeAndDeletionRevokeOpenSessions() throws Exception {
        int id=staff();
        try(Store other=new Store(owner.directory)) {
            other.login("second","review-password");
            owner.save(Store.Entity.STAFF,id,Map.of("cin","second","nom","Other","prenom","Admin","role","2"));
            assertThrows(Store.Rule.class,()->other.list(Store.Entity.STAFF)); assertNull(other.user());
            other.login("second","review-password"); owner.delete(Store.Entity.STAFF,id);
            assertThrows(Store.Rule.class,()->other.save(Store.Entity.CATEGORIES,null,Map.of("cat_libelle","Denied"))); assertNull(other.user());
        }
    }
    @Test void failedLoginDoesNotCreateSession() throws Exception {
        owner.logout();
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+owner.directory.resolve("test1.db"));Statement sql=c.createStatement()) {
            sql.execute("CREATE TRIGGER fail_login BEFORE INSERT ON trace BEGIN SELECT RAISE(ABORT,'test failure'); END");
            assertThrows(SQLException.class,()->owner.login("owner","review-password")); assertNull(owner.user());
        }
    }
    @Test void passwordChangeRequiresCurrentPasswordAndRevokesOtherSessions() throws Exception {
        try(Store other=new Store(owner.directory)) {
            other.login("owner","review-password");
            assertThrows(Store.Rule.class,()->owner.changePassword("wrong", "replacement-password"));
            owner.changePassword("review-password","replacement-password");
            assertNotNull(owner.statistics()); assertThrows(Store.Rule.class,other::statistics);
            owner.logout(); assertThrows(Store.Rule.class,()->owner.login("owner","review-password")); owner.login("owner","replacement-password");
        }
    }
    @Test void restoreArchivePreservesIdentityAndHistory() throws Exception {
        int book=book(), student=student(); owner.borrow(book,student,LocalDate.now().plusDays(2));
        int loan=owner.loans(false,null,null).getFirst().number("emp_id");owner.returnLoan(loan);
        owner.delete(Store.Entity.STUDENTS,student);assertEquals(student,owner.list(Store.Entity.STUDENTS,true).getFirst().number("etud_id"));
        owner.restore(Store.Entity.STUDENTS,student);assertEquals(student,owner.list(Store.Entity.STUDENTS).getFirst().number("etud_id"));
        assertEquals(1,owner.loans(true,Store.Entity.STUDENTS,student).size());
    }
    @Test void renewAndReturnRecordDatesAndActor() throws Exception {
        owner.borrow(book(),student(),LocalDate.now().plusDays(2));int id=owner.loans(false,null,null).getFirst().number("emp_id");
        assertThrows(Store.Rule.class,()->owner.renewLoan(id,LocalDate.now().plusDays(1)));
        owner.renewLoan(id,LocalDate.now().plusDays(8));assertEquals("1",owner.loans(false,null,null).getFirst().text("renewals"));
        owner.returnLoan(id);var row=owner.loans(true,null,null).getFirst();assertFalse(row.text("returned_at").isBlank());assertEquals(owner.user().id(),row.number("returned_by"));
        assertThrows(Store.Rule.class,()->owner.renewLoan(id,LocalDate.now().plusDays(10)));
    }
    @Test void staleEditorCannotOverwriteNewChanges() throws Exception {
        int id=student();var first=new HashMap<>(owner.list(Store.Entity.STUDENTS).getFirst().values());first.put("_revision",first.get("revision"));
        var second=new HashMap<>(first);second.put("phone","456");owner.save(Store.Entity.STUDENTS,id,second);
        first.put("nom","New name");assertEquals("recordChanged",assertThrows(Store.Rule.class,()->owner.save(Store.Entity.STUDENTS,id,first)).key);
        assertEquals("456",owner.list(Store.Entity.STUDENTS).getFirst().text("phone"));
    }
    @Test void portableBackupRestoresImagesAndCreatesRecoveryArchive() throws Exception {
        int id=book();String image=ImageStorage.importImage(Path.of("packaging/papyrus.png"),owner.directory);
        var values=new HashMap<>(owner.list(Store.Entity.BOOKS).getFirst().values());values.put("image",image);owner.save(Store.Entity.BOOKS,id,values);
        assertTrue(image.startsWith("images/"));
        Path backup=directory.resolve("library.zip");BackupArchive.create(owner,backup);
        try(Store restored=new Store(directory.resolve("elsewhere"))) {
            restored.setup("different","Different owner","different-password");restored.login("different","different-password");
            Path recovery=BackupArchive.restore(restored,backup);assertTrue(Files.isRegularFile(recovery));assertNull(restored.user());
            restored.login("owner","review-password");var row=restored.list(Store.Entity.BOOKS).getFirst();
            assertEquals(id,row.number("doc_id"));assertTrue(Files.isRegularFile(ImageStorage.resolve(row.text("image"),restored.directory)));
        }
    }
    @Test void unsafeArchiveFailsWithoutChangingLibrary() throws Exception {
        int id=book();Path backup=directory.resolve("bad.zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(backup))) {zip.putNextEntry(new ZipEntry("../escape"));zip.write(1);zip.closeEntry();}
        assertThrows(java.io.IOException.class,()->BackupArchive.restore(owner,backup));assertEquals(id,owner.list(Store.Entity.BOOKS).getFirst().number("doc_id"));
        assertFalse(Files.exists(directory.resolve("escape")));
    }
    @Test void importRollsBackEntireFileOnDuplicate() throws Exception {
        var row=Map.of("doc_libelle","Title","category","Imported category","type","Book","copy_code","ONE");
        assertThrows(Store.ImportFailure.class,()->owner.importRecords(Store.Entity.BOOKS,List.of(row,row)));
        assertTrue(owner.list(Store.Entity.BOOKS).isEmpty());assertTrue(owner.list(Store.Entity.CATEGORIES).isEmpty());
    }
    @Test void csvRoundTripsQuotedArabicAndFormulaLikeValues() throws Exception {
        Path csv=directory.resolve("students.csv");var row=new Store.Row(Map.of("cne","ST,1","prenom","أمين\nمحمد","nom","O\"Neil","phone","+212123","email","=formula"));
        CsvTransfer.export(csv,Store.Entity.STUDENTS,List.of(row));assertTrue(Files.readString(csv).contains("'=formula"));
        var parsed=CsvTransfer.read(csv,Store.Entity.STUDENTS).getFirst();for(String field:row.values().keySet())assertEquals(row.text(field),parsed.get(field));
        assertThrows(java.io.IOException.class,()->CsvTransfer.parse("\"unterminated"));
    }
    @Test void migrationBacksUpOldSchemaAndAddsLookupIndex() throws Exception {
        owner.close();try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("library/test1.db"));Statement sql=c.createStatement()){sql.execute("PRAGMA user_version=10000");}
        owner=new Store(directory.resolve("library"));assertTrue(Files.isRegularFile(owner.directory.resolve("test1.pre-1.1.0.db")));
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+owner.directory.resolve("test1.db"));Statement sql=c.createStatement();ResultSet r=sql.executeQuery("EXPLAIN QUERY PLAN SELECT 1 FROM emprunts WHERE doc_id=1 AND status=1 AND emp_state=1")) {
            assertTrue(r.next());assertTrue(r.getString("detail").contains("loan_availability"));
        }
    }
    @Test void restoreRequiresOtherConnectionsToClose() throws Exception {
        Path backup=directory.resolve("backup.zip");BackupArchive.create(owner,backup);
        try(Store other=new Store(owner.directory)) {
            assertEquals("closeOtherWindows",assertThrows(Store.Rule.class,()->BackupArchive.restore(owner,backup)).key);
            assertNotNull(owner.user());
        }
        BackupArchive.restore(owner,backup);assertNull(owner.user());
    }
    @Test void checksumFailurePreservesExistingRecords() throws Exception {
        int id=book();Path original=directory.resolve("original.zip"),changed=directory.resolve("changed.zip");BackupArchive.create(owner,original);
        try(ZipInputStream input=new ZipInputStream(Files.newInputStream(original));ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(changed))) {
            ZipEntry entry;while((entry=input.getNextEntry())!=null){output.putNextEntry(new ZipEntry(entry.getName()));byte[] bytes=input.readAllBytes();if(entry.getName().equals("test1.db"))bytes[0]^=1;output.write(bytes);output.closeEntry();}
        }
        assertThrows(java.io.IOException.class,()->BackupArchive.restore(owner,changed));assertEquals(id,owner.list(Store.Entity.BOOKS).getFirst().number("doc_id"));
    }
    @Test void existingAbsoluteManagedReferencesResolveAfterMove() throws Exception {
        String image=ImageStorage.importImage(Path.of("packaging/papyrus.png"),owner.directory);Path original=owner.directory.resolve(image);
        String reference=original.toUri().toString();owner.close();Path moved=directory.resolve("moved");Files.move(directory.resolve("library"),moved);
        owner=new Store(moved);assertEquals(moved.resolve(image),ImageStorage.resolve(reference,moved));assertEquals(image,ImageStorage.managedReference(reference,moved));
    }
    @Test void malformedImageDoesNotCreateManagedFiles() throws Exception {
        Path invalid=directory.resolve("invalid.png");Files.writeString(invalid,"not an image");assertThrows(java.io.IOException.class,()->ImageStorage.importImage(invalid,owner.directory));
        assertFalse(Files.exists(owner.directory.resolve("images")));
    }

}
