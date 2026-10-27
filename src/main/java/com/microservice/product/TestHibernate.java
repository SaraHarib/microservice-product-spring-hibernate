package com.microservice.product;

import com.microservice.product.config.AppConfig;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import org.hibernate.SessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class TestHibernate {
    public static void main(String[] args) throws SQLException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
            System.out.println("[OK] Contexte Spring initialisé.");
            if (context.getBean(SessionFactory.class).isClosed()) {
                throw new IllegalStateException("La SessionFactory est fermée.");
            }
            System.out.println("[OK] SessionFactory Hibernate initialisée.");
            validateDatabase(context.getBean(DataSource.class));
            System.out.println("[OK] Connexion product_db validée.");
        } finally {
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    static void validateDatabase(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !"product_db".equals(result.getString(1))) {
                throw new IllegalStateException("La connexion doit utiliser product_db.");
            }
        }
    }
}
