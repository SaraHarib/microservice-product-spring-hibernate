package com.microservice.product;

import com.microservice.product.config.AppConfig;
import com.microservice.product.dao.IDao;
import com.microservice.product.dao.ProductDaoImpl;
import com.microservice.product.entity.Product;
import com.microservice.product.service.ProductService;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import org.hibernate.SessionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.orm.hibernate5.HibernateTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainApp {
    private static final String SEPARATOR = "----------------------------------------";

    public static void main(String[] args) throws SQLException {
        System.out.println("========================================");
        System.out.println("MICROSERVICE PRODUCT - SPRING + HIBERNATE");
        System.out.println("========================================");
        section(1, "CONFIGURATION SPRING / HIBERNATE");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
            check(context.isActive(), "Contexte Spring initialisé.");
            DataSource dataSource = context.getBean(DataSource.class);
            TestHibernate.validateDatabase(dataSource);
            check(dataSource != null, "DataSource configurée.");
            SessionFactory sessionFactory = context.getBean(SessionFactory.class);
            check(!sessionFactory.isClosed(), "SessionFactory Hibernate initialisée.");
            HibernateTransactionManager manager = context.getBean(HibernateTransactionManager.class);
            check(manager.getSessionFactory() == sessionFactory, "TransactionManager configuré.");
            ProductService service = context.getBean(ProductService.class);
            List<Product> ownedProducts = new ArrayList<>();
            String run = " [DEMO " + UUID.randomUUID() + "]";
            try {
                section(2, "CREATION D'UN PRODUIT");
                Product product = new Product("Microservice Gateway" + run, 149.99);
                ownedProducts.add(product);
                System.out.println("Nom : " + product.getName());
                System.out.println("Prix initial : 149.99");
                service.addProduct(product);
                System.out.println("Produit créé : " + product);
                check(product.getId() != null, "Création via ProductService validée.");

                section(3, "LECTURE DES PRODUITS");
                List<Product> products = service.getAllProducts();
                products.forEach(System.out::println);
                System.out.println("Nombre de produits trouvés : " + products.size());
                check(products.stream().anyMatch(p -> p.getId().equals(product.getId())),
                        "Lecture via DAO générique validée.");

                section(4, "RECHERCHE PAR ID");
                System.out.println("ID recherché : " + product.getId());
                Product found = service.getProductById(product.getId());
                System.out.println("Produit retrouvé : " + found);
                check(found != null && found.getName().equals(product.getName()), "findById validé.");

                section(5, "MISE A JOUR");
                double oldPrice = found.getPrice();
                found.setPrice(129.99);
                service.updateProduct(found);
                Product updated = service.getProductById(product.getId());
                System.out.printf(Locale.ROOT, "Ancienne valeur : %.2f%nNouvelle valeur relue en base : %.2f%n",
                        oldPrice, updated.getPrice());
                check(Double.compare(updated.getPrice(), 129.99) == 0, "Update transactionnel validé.");

                section(6, "DAO GENERIQUE IDao<Product>");
                ProductDaoImpl dao = context.getBean(ProductDaoImpl.class);
                Class<?> entityType = ResolvableType.forClass(dao.getClass()).as(IDao.class).getGeneric(0).resolve();
                System.out.println("ProductDaoImpl implémente IDao<Product>.");
                System.out.println("ProductServiceImpl reçoit IDao<Product> par injection constructeur.");
                System.out.println("CRUD : save / findById / findAll / update / delete via la même abstraction.");
                check(entityType == Product.class, "Pattern DAO générique validé.");

                section(7, "TRANSACTIONS ET ROLLBACK");
                check(AopUtils.isAopProxy(service), "Proxy transactionnel Spring actif.");
                Product negative = new Product("Prix négatif refusé" + run, -10);
                ownedProducts.add(negative);
                expectNegativePrice(() -> service.addProduct(negative));
                check(negative.getId() == null && !containsName(service, negative.getName()),
                        "Produit négatif absent de product_db.");

                Product rollbackProduct = new Product("Insertion annulée par rollback" + run, 25);
                ownedProducts.add(rollbackProduct);
                System.out.println("Lot atomique : INSERT valide, puis erreur métier sur le second produit.");
                expectNegativePrice(() -> service.addProducts(List.of(rollbackProduct, negative)));
                check(rollbackProduct.getId() != null
                                && service.getProductById(rollbackProduct.getId()) == null
                                && !containsName(service, negative.getName()),
                        "Rollback réel confirmé : le premier INSERT a été annulé.");
                check(true, "Transaction / validation métier validée.");

                section(8, "SUPPRESSION");
                System.out.println("Suppression du produit de démonstration : " + product.getId());
                service.deleteProduct(updated);
                check(service.getProductById(product.getId()) == null, "Delete validé.");
            } finally {
                // Ne nettoyer que les identifiants créés par cette exécution.
                for (Product owned : ownedProducts) {
                    if (owned.getId() != null) {
                        Product remaining = service.getProductById(owned.getId());
                        if (remaining != null) {
                            service.deleteProduct(remaining);
                        }
                    }
                }
            }

            section(9, "VERIFICATION FINALE");
            System.out.println("[OK] Configuration Spring validée.");
            System.out.println("[OK] Hibernate / SessionFactory validé.");
            System.out.println("[OK] Connexion product_db validée.");
            System.out.println("[OK] Entité Product validée.");
            System.out.println("[OK] IDao<T> validé.");
            System.out.println("[OK] CRUD Product validé.");
            System.out.println("[OK] Injection Spring validée.");
            System.out.println("[OK] @Transactional validé.");
            System.out.println("[OK] Validation et rollback validés.");
            System.out.println("[OK] Toutes les vérifications du TP Microservice Product ont réussi.");
            System.out.println("Données de cette démonstration nettoyées ; exécution réexécutable.");
        } finally {
            // Libérer aussi le thread du pilote lors d'une exécution dans la JVM Maven.
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    private static void section(int number, String title) {
        System.out.println();
        System.out.println(number + ". " + title);
        System.out.println(SEPARATOR);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Vérification échouée : " + message);
        }
        System.out.println("[OK] " + message);
    }

    private static boolean containsName(ProductService service, String name) {
        return service.getAllProducts().stream().anyMatch(product -> name.equals(product.getName()));
    }

    private static void expectNegativePrice(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException exception) {
            if (!"Le prix ne peut pas être négatif".equals(exception.getMessage())) {
                throw exception;
            }
            System.out.println("Erreur métier détectée : " + exception.getMessage());
            return;
        }
        throw new IllegalStateException("Le prix négatif aurait dû être refusé.");
    }
}
