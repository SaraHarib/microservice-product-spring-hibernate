package com.microservice.product;

import com.microservice.product.config.AppConfig;
import com.microservice.product.dao.IDao;
import com.microservice.product.dao.ProductDaoImpl;
import com.microservice.product.entity.Product;
import com.microservice.product.service.ProductService;
import com.microservice.product.service.ProductServiceImpl;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ReflectionUtils;

import javax.persistence.metamodel.EntityType;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests d'intégration : chaque appel de service ouvre sa vraie transaction sur product_db. */
class ProductIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static ProductService service;
    private static SessionFactory sessionFactory;
    private final List<Product> ownedProducts = new ArrayList<>();

    @BeforeAll
    static void startContext() {
        context = new AnnotationConfigApplicationContext(AppConfig.class);
        service = context.getBean(ProductService.class);
        sessionFactory = context.getBean(SessionFactory.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void cleanOnlyOwnedProducts() {
        for (Product product : ownedProducts) {
            if (product.getId() != null) {
                Product persisted = service.getProductById(product.getId());
                if (persisted != null) {
                    service.deleteProduct(persisted);
                }
            }
        }
    }

    @Test
    void springContextStarts() {
        assertTrue(context.isActive());
    }

    @Test
    void dataSourceIsPresent() {
        assertNotNull(context.getBean(DataSource.class));
    }

    @Test
    void sessionFactoryIsPresentAndOpen() {
        assertNotNull(sessionFactory);
        assertFalse(sessionFactory.isClosed());
    }

    @Test
    void productDaoIsDetected() {
        assertNotNull(context.getBean(ProductDaoImpl.class));
    }

    @Test
    void productServiceImplementationIsDetectedAndProxied() {
        assertEquals(ProductServiceImpl.class, AopUtils.getTargetClass(service));
        assertTrue(AopUtils.isAopProxy(service));
        assertSame(service, context.getBean("productServiceImpl"));
    }

    @Test
    void transactionManagerUsesHibernateSessionFactory() {
        HibernateTransactionManager manager = context.getBean(HibernateTransactionManager.class);
        assertSame(sessionFactory, manager.getSessionFactory());
    }

    @Test
    void connectsToRealProductDatabaseWithTransactionalTable() throws Exception {
        DataSource dataSource = context.getBean(DataSource.class);
        TestHibernate.validateDatabase(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:3308/product_db"));
            assertTrue(connection.getMetaData().getDriverName().contains("MySQL"));
            try (ResultSet result = statement.executeQuery(
                    "SELECT ENGINE FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = 'product_db' AND TABLE_NAME = 'products'")) {
                assertTrue(result.next());
                assertEquals("InnoDB", result.getString(1));
            }
        }
    }

    @Test
    void productIsMappedAsJpaEntity() {
        EntityType<Product> entity = sessionFactory.getMetamodel().entity(Product.class);
        assertEquals(Long.class, entity.getIdType().getJavaType());
        assertEquals(String.class, entity.getAttribute("name").getJavaType());
        assertEquals(double.class, entity.getAttribute("price").getJavaType());
    }

    @Test
    void genericProductDaoIsConstructorInjected() throws Exception {
        assertEquals(Product.class, ResolvableType.forClass(ProductDaoImpl.class)
                .as(IDao.class).getGeneric(0).resolve());
        assertEquals(Product.class, ResolvableType.forConstructorParameter(
                ProductServiceImpl.class.getConstructor(IDao.class), 0).getGeneric(0).resolve());
        Object target = ((Advised) service).getTargetSource().getTarget();
        Field daoField = ProductServiceImpl.class.getDeclaredField("productDao");
        ReflectionUtils.makeAccessible(daoField);
        assertSame(context.getBean(ProductDaoImpl.class), daoField.get(target));
    }

    @Test
    void createsAndCommitsProductWithGeneratedIdentity() {
        Product product = newProduct("create", 149.99);
        assertNull(product.getId());
        service.addProduct(product);
        assertNotNull(product.getId());
        assertTrue(product.getId() > 0);
        Product persisted = service.getProductById(product.getId());
        assertNotNull(persisted);
        assertNotSame(product, persisted);
        assertEquals(product.getName(), persisted.getName());
        assertEquals(149.99, persisted.getPrice(), 0.0001);
    }

    @Test
    void findsProductByIdInANewTransaction() {
        Product product = persistProduct("findById", 50);
        Product found = service.getProductById(product.getId());
        assertNotNull(found);
        assertEquals(product.getId(), found.getId());
        assertEquals(product.getName(), found.getName());
    }

    @Test
    void findsAllIncludingProductsCreatedByThisTest() {
        Product first = persistProduct("findAll-first", 10);
        Product second = persistProduct("findAll-second", 20);
        List<Product> products = service.getAllProducts();
        assertTrue(products.stream().anyMatch(p -> p.getId().equals(first.getId())));
        assertTrue(products.stream().anyMatch(p -> p.getId().equals(second.getId())));
    }

    @Test
    void updatesDetachedProductAndCommitsChanges() {
        Product product = persistProduct("update", 100);
        product.setName(product.getName() + " updated");
        product.setPrice(129.99);
        service.updateProduct(product);
        Product updated = service.getProductById(product.getId());
        assertEquals(product.getName(), updated.getName());
        assertEquals(129.99, updated.getPrice(), 0.0001);
    }

    @Test
    void deletesProductAndReturnsNullForMissingId() {
        Product product = persistProduct("delete", 15);
        service.deleteProduct(product);
        assertNull(service.getProductById(product.getId()));
    }

    @Test
    void rejectsNegativePriceWithoutPersistingProduct() {
        Product product = newProduct("negative-create", -1);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.addProduct(product));
        assertEquals("Le prix ne peut pas être négatif", exception.getMessage());
        assertNull(product.getId());
        assertFalse(service.getAllProducts().stream().anyMatch(p -> product.getName().equals(p.getName())));
    }

    @Test
    void rejectsNegativeUpdateAndPreservesCommittedValues() {
        Product product = persistProduct("negative-update", 80);
        String originalName = product.getName();
        product.setPrice(-20);
        product.setName(originalName + " refused");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateProduct(product));
        assertEquals("Le prix ne peut pas être négatif", exception.getMessage());
        Product unchanged = service.getProductById(product.getId());
        assertEquals(originalName, unchanged.getName());
        assertEquals(80, unchanged.getPrice(), 0.0001);
    }

    @Test
    void rollsBackAnActualIdentityInsertAfterBusinessError() {
        Product valid = newProduct("rollback-valid", 25);
        Product invalid = newProduct("rollback-invalid", -5);
        assertThrows(IllegalArgumentException.class, () -> service.addProducts(List.of(valid, invalid)));
        // IDENTITY impose un INSERT immédiat pour obtenir cet identifiant avant l'erreur.
        assertNotNull(valid.getId(), "Le premier produit doit avoir réellement été inséré avant le rollback.");
        assertNull(service.getProductById(valid.getId()), "L'INSERT doit être annulé en base.");
        assertNull(invalid.getId());
        assertFalse(service.getAllProducts().stream().anyMatch(p ->
                valid.getName().equals(p.getName()) || invalid.getName().equals(p.getName())));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void commitsAnEntireValidBatch() {
        Product first = newProduct("batch-first", 10);
        Product second = newProduct("batch-second", 20);
        service.addProducts(List.of(first, second));
        assertNotNull(service.getProductById(first.getId()));
        assertNotNull(service.getProductById(second.getId()));
    }

    @Test
    void acceptsZeroPrice() {
        Product product = persistProduct("free", 0);
        assertEquals(0, service.getProductById(product.getId()).getPrice());
    }

    @Test
    void readMethodsActuallyRunInsideReadOnlyTransactions() {
        Product product = persistProduct("read-only", 30);
        assertTransactionDuring(() -> service.getAllProducts(), true);
        assertTransactionDuring(() -> service.getProductById(product.getId()), true);
    }

    @Test
    void mutationMethodsActuallyRunInsideWritableTransactions() {
        Product product = newProduct("writable", 40);
        assertTransactionDuring(() -> service.addProduct(product), false);
        product.setPrice(45);
        assertTransactionDuring(() -> service.updateProduct(product), false);
        assertTransactionDuring(() -> service.deleteProduct(product), false);
        Product batchProduct = newProduct("writable-batch", 50);
        assertTransactionDuring(() -> service.addProducts(List.of(batchProduct)), false);
    }

    @Test
    void daoRequiresASpringManagedTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        ProductDaoImpl dao = context.getBean(ProductDaoImpl.class);
        assertThrows(HibernateException.class, dao::findAll);
        assertFalse(TransactionSynchronizationManager.hasResource(sessionFactory));
    }

    private Product newProduct(String label, double price) {
        Product product = new Product("[TEST " + UUID.randomUUID() + "] " + label, price);
        ownedProducts.add(product);
        return product;
    }

    private Product persistProduct(String label, double price) {
        Product product = newProduct(label, price);
        service.addProduct(product);
        return product;
    }

    private void assertTransactionDuring(Runnable operation, boolean readOnly) {
        List<Boolean> observedReadOnly = new ArrayList<>();
        Advised proxy = (Advised) service;
        // Ajout après l'intercepteur transactionnel : observation du vrai appel, sans mock.
        MethodInterceptor observer = invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(sessionFactory.getCurrentSession().isJoinedToTransaction());
            assertEquals(readOnly, sessionFactory.getCurrentSession().isDefaultReadOnly());
            observedReadOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.proceed();
        };
        proxy.addAdvice(observer);
        try {
            operation.run();
            assertEquals(List.of(readOnly), observedReadOnly);
        } finally {
            proxy.removeAdvice(observer);
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(sessionFactory));
    }
}
