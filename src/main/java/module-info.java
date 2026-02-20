module me.maxih.itunes_backup_explorer {
    requires javafx.controls;
    requires javafx.fxml;
    requires dd.plist;
    requires org.bouncycastle.provider;
    requires java.sql;
    requires java.desktop;
    requires java.prefs;
    requires org.xerial.sqlitejdbc;
    requires org.slf4j;
    requires org.slf4j.simple;
    requires org.apache.commons.compress;

    opens me.maxih.itunes_backup_explorer.ui to javafx.fxml;
    exports me.maxih.itunes_backup_explorer;
    exports me.maxih.itunes_backup_explorer.ui;
    exports me.maxih.itunes_backup_explorer.api;
    exports me.maxih.itunes_backup_explorer.util;
}
