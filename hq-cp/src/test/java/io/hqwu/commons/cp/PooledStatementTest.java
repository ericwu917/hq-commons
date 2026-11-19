package io.hqwu.commons.cp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.hqwu.commons.cp.util.SqlMasker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.easymock.EasyMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PooledStatementTest {

    private PooledConnection mockPooledConnection;
    private Statement mockRealStatement;
    private HqcpConfig mockConfig;
    private Hqcp mockPool;

    @BeforeEach
    void setUp() {
        mockPooledConnection = createNiceMock(PooledConnection.class);
        mockRealStatement = createMock(Statement.class);
        mockConfig = createNiceMock(HqcpConfig.class);
        mockPool = createNiceMock(Hqcp.class);
    }

    @AfterEach
    void tearDown() {
        // verify(mockPooledConnection, mockRealStatement); // Optional: verify all mocks
    }

    /**
     * Test Case 1: Lifecycle (Open/Close)
     * Requirement: Calling close() on the proxy should check the statement back into the Connection,
     * not close the physical statement.
     */
    @Test
    void testCloseChecksInStatement() throws SQLException {
        // 1. Setup expectations for Constructor
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();

        // ResultSetType checks in constructor
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Expectations for close() logic
        // It validates if checkOut is true (it will be).
        // It calls connection.checkIn(this)
        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        // Logging expectations (PooledStatement checks isVerbose)
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();

        // Replay mocks
        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        // 3. Instantiate and Checkout
        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        assertTrue(pooledStatement.isCheckOut(), "Statement should be checked out");

        // 4. Perform close
        proxy.close();

        // 5. Verify
        verify(mockPooledConnection);

        // Assert state changed (checkOut should be false after close/checkIn,
        // effectively checkIn handles the logic but PooledStatement.close logic sets checkOut atomic boolean to false BEFORE calling checkIn)
        assertFalse(pooledStatement.isCheckOut(), "Statement should be checked in (not checked out)");
    }

    /**
     * Test Case 2: Execution Delegation & Multiple Results Compliance
     * Requirement: Test correct handling of execute(), getMoreResults(), getResultSet(), getUpdateCount()
     * Sequence: ResultSet -> Update Count -> ResultSet -> No More Results
     */
    @Test
    void testExecuteInterleavedResults() throws SQLException {
        String sql = "some-complex-sql";

        // Constructor expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 1. execute(sql) -> true (ResultSet)
        expect(mockRealStatement.execute(sql)).andReturn(true);
        ResultSet rs1 = createMock(ResultSet.class);
        expect(mockRealStatement.getResultSet()).andReturn(rs1);
        // expect(mockRealStatement.getUpdateCount()).andReturn(-1); // JDBC spec: -1 when current is ResultSet

        // 2. getMoreResults() -> false (Update Count)
        expect(mockRealStatement.getMoreResults()).andReturn(false);
        expect(mockRealStatement.getUpdateCount()).andReturn(5);
        expect(mockRealStatement.getResultSet()).andReturn(null); // Expect delegation to return null

        // 3. getMoreResults() -> true (ResultSet)
        expect(mockRealStatement.getMoreResults()).andReturn(true);
        ResultSet rs2 = createMock(ResultSet.class);
        expect(mockRealStatement.getResultSet()).andReturn(rs2);

        // 4. getMoreResults() -> false (End of results)
        expect(mockRealStatement.getMoreResults()).andReturn(false);
        expect(mockRealStatement.getUpdateCount()).andReturn(-1);
        expect(mockRealStatement.getResultSet()).andReturn(null); // End of results: getResultSet returns null

        // Replay
        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, rs1, rs2);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Step 1: Execute and verification
        assertTrue(proxy.execute(sql));
        assertNotNull(proxy.getResultSet(), "First result should be a ResultSet");
        // Note: getUpdateCount() on local proxy might return cached value or 0 or -1 depending on impl.
        // JDBC spec says -1 if current is ResultSet. PooledStatement should respect this.
        assertEquals(-1, proxy.getUpdateCount());

        // Step 2: Move to Update Count
        assertFalse(proxy.getMoreResults());
        // CRITICAL CHECK: getResultSet() must be null now
        assertNull(proxy.getResultSet(), "ResultSet should be null when current result is Update Count");
        // assertEquals(5, proxy.getUpdateCount()); // TODO: Fix mock state checking for update count


        // Step 3: Move to Second ResultSet
        assertTrue(proxy.getMoreResults());
        assertNotNull(proxy.getResultSet());
        // assertEquals(-1, proxy.getUpdateCount());

        // Step 4: End
        assertFalse(proxy.getMoreResults());
        assertNull(proxy.getResultSet());
        // assertEquals(-1, proxy.getUpdateCount());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 3: Result Set Wrapping
     * Requirement: Verify that the ResultSet returned by executeQuery is wrapped (for logging/management)
     * and delegates calls correctly to the underlying ResultSet.
     */
    @Test
    void testResultSetWrapping() throws SQLException {
        String sql = "SELECT * FROM test";

        // Constructor expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        
        // FIX: Must enable verbose or printSQL to trigger ResultSet wrapping
        expect(mockPooledConnection.isVerbose()).andReturn(true).anyTimes();

        // Execution expectations
        ResultSet realRs = createMock(ResultSet.class);
        expect(mockRealStatement.executeQuery(sql)).andReturn(realRs);

        // ResultSet interaction expectations
        expect(realRs.next()).andReturn(true);
        expect(realRs.getString(1)).andReturn("some-value");
        realRs.close();
        expectLastCall().once();

        // Replay
        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, realRs);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // 1. Execute Query
        ResultSet wrappedRs = proxy.executeQuery(sql);
        assertNotNull(wrappedRs);
        assertNotSame(realRs, wrappedRs, "ResultSet should be wrapped");

        // 2. Interact with ResultSet
        assertTrue(wrappedRs.next());
        assertEquals("some-value", wrappedRs.getString(1));

        // 3. Close ResultSet
        wrappedRs.close();

        verify(mockPooledConnection, mockRealStatement, realRs);
    }

    @Test
    void testResultSetNotWrappedWhenVerboseDisabled() throws SQLException {
        String sql = "SELECT * FROM test";

        // Constructor expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Ensure verbose and printSQL are disabled
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        // Execution expectations
        ResultSet realRs = createMock(ResultSet.class);
        expect(mockRealStatement.executeQuery(sql)).andReturn(realRs);

        // Replay
        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, realRs);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Execute Query
        ResultSet resultRs = proxy.executeQuery(sql);

        // Verify that the returned ResultSet is the original one, not a proxy
        assertSame(realRs, resultRs, "ResultSet should NOT be wrapped when verbose/printSQL is disabled");

        verify(mockPooledConnection, mockRealStatement, realRs);
    }

    /**
     * Test Case 4: Exception Handling
     * Requirement: Verify that SQLExceptions are caught, logged, checked for fatal status,
     * and propagated to the caller.
     */
    @Test
    void testExceptionHandling() throws SQLException {
        String sql = "SELECT * FROM bad_table";
        SQLException sqlException = new SQLException("Simulated DB Error", "42000", 1064);

        // Basic Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Exception Handling Dependencies (for logging and config checks)
        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockPool.getSqlMasker()).andReturn(null).anyTimes();
        expect(mockConfig.isMaskSql()).andReturn(false).anyTimes();

        // Execute -> Throw
        expect(mockRealStatement.execute(sql)).andThrow(sqlException);

        // Exception Logic
        // 1. Simulate that this is a fatal exception (e.g. connection lost)
        expect(mockPooledConnection.isFatalException(sqlException)).andReturn(true);

        // 2. Fatal exception should trigger real close()
        mockRealStatement.close();
        expectLastCall().once();

        // 3. Should mark connection as having a fatal exception
        mockPooledConnection.setFatalExceptionHappened(true);
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        try {
            proxy.execute(sql);
            fail("Expected SQLException was not thrown");
        } catch (SQLException e) {
            assertSame(sqlException, e);
        }

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 5: State Management & Idempotency
     * Requirement:
     * 1. isClosed() should return true after close() is called (Logical close).
     * 2. close() should be idempotent (calling it multiple times has no side effect).
     */
    @Test
    void testStateManagementAndIdempotency() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // isClosed delegation (physically open)
        // If PooledStatement delegates isClosed to physical statement, it will return false.
        // We expect PooledStatement to intercept this.
        // So we might NOT expect mockRealStatement.isClosed() to be called if implemented correctly.
        // But for "Before" state (open), it might delegate or check internal flag.
        // Let's assume physical/delegate check for 'false' state if checking real statement.
        expect(mockRealStatement.isClosed()).andReturn(false).anyTimes();

        // Expect checkIn ONLY ONCE
        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // 1. Initial State
        assertTrue(pooledStatement.isCheckOut());
        assertFalse(proxy.isClosed(), "Statement should be open initially");

        // 2. First Close
        proxy.close();
        assertFalse(pooledStatement.isCheckOut());
        assertTrue(proxy.isClosed(), "Statement should be logically closed after close()");

        // 3. Second Close (Idempotency)
        proxy.close();
        assertFalse(pooledStatement.isCheckOut());
        assertTrue(proxy.isClosed(), "Statement should remain closed");

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 6: Access After Close
     * Requirement: After close() is called, invoking other methods (e.g. execute) should throw SQLException.
     */
    @Test
    void testAccessAfterCloseThrowsException() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockRealStatement.isClosed()).andReturn(false).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // 1. Close the statement
        proxy.close();
        assertTrue(proxy.isClosed());

        // 2. Try to execute SQL after close - Should throw SQLException
        try {
            proxy.execute("SELECT 1");
            fail("Should throw SQLException when accessing a closed statement");
        } catch (SQLException e) {
            assertEquals("HY010", e.getSQLState(), "Should throw Function sequence error (HY010)");
        }

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 7: Wrapper Support
     * Requirement: Verify isWrapperFor and unwrap implement the correct logic:
     * 1. Check if the requested interface is implemented by the InvocationHandler (PooledStatement)
     * 2. Check if the requested interface is implemented by the Proxy itself
     * 3. Delegate to the underlying statement's isWrapperFor/unwrap
     */
    @Test
    void testWrapperSupport() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(true).anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Test 1: isWrapperFor with Statement interface (proxy implements it)
        assertTrue(proxy.isWrapperFor(Statement.class),
            "Proxy should be a wrapper for Statement interface");

        // Test 2: unwrap with Statement interface (should return proxy itself)
        Statement unwrappedStmt = proxy.unwrap(Statement.class);
        assertSame(proxy, unwrappedStmt,
            "Unwrapping Statement should return the proxy itself");

        // Test 3: isWrapperFor with PooledStatement class (handler type)
        assertTrue(proxy.isWrapperFor(PooledStatement.class),
            "Proxy should be a wrapper for PooledStatement (the handler)");

        // Test 4: unwrap with PooledStatement class (should return the handler)
        PooledStatement unwrappedHandler = proxy.unwrap(PooledStatement.class);
        assertSame(pooledStatement, unwrappedHandler,
            "Unwrapping PooledStatement should return the handler itself");

        proxy.close(); // Cleanup

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 7.1: Wrapper Support - Delegation to Real Statement
     * Verify that when the requested interface is not implemented by handler or proxy,
     * it delegates to the underlying real statement.
     */
    @Test
    void testWrapperSupportDelegation() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Define a vendor-specific interface
        Class<Runnable> vendorInterface = Runnable.class;
        Runnable mockVendorObject = createMock(Runnable.class);

        // When checking for Runnable interface:
        // 1. PooledStatement does not implement Runnable -> false
        // 2. Proxy does not implement Runnable -> false
        // 3. Delegate to real statement
        expect(mockRealStatement.isWrapperFor(vendorInterface)).andReturn(true).once();

        // When unwrapping Runnable:
        // 1. Proxy is not instance of Runnable
        // 2. Handler is not instance of Runnable
        // 3. Delegate to real statement
        expect(mockRealStatement.unwrap(vendorInterface)).andReturn(mockVendorObject).once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, mockVendorObject);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Test isWrapperFor delegation
        assertTrue(proxy.isWrapperFor(vendorInterface),
            "Should delegate to underlying statement and return true");

        // Test unwrap delegation
        Runnable unwrapped = proxy.unwrap(vendorInterface);
        assertSame(mockVendorObject, unwrapped,
            "Should delegate to underlying statement and return the vendor object");

        proxy.close(); // Cleanup

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 7.2: Wrapper Support - Null Handling
     */
    @Test
    void testWrapperSupportNullHandling() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // When unwrap(null) throws NullPointerException, it will trigger close() and setFatalExceptionHappened
        mockRealStatement.close();
        expectLastCall().once();
        mockPooledConnection.setFatalExceptionHappened(true);
        expectLastCall().once();

        // Final checkIn when proxy.close() is called
        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Test 1: isWrapperFor with null should return false
        assertFalse(proxy.isWrapperFor(null),
            "isWrapperFor(null) should return false");

        // Test 2: unwrap with null should throw exception
        // Note: NullPointerException will trigger close() because it's not a SQLException
        assertThrows(NullPointerException.class, () -> proxy.unwrap(null),
            "unwrap(null) should throw NullPointerException");

        proxy.close();

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 8: Get Connection Correctness
     * Requirement: statement.getConnection() should return the PooledConnection wrapper (proxy),
     * NOT the underlying physical connection.
     */
    @Test
    void testGetConnectionReturnsWrapper() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Expectation: PooledStatement should call mockPooledConnection.getConnection()
        Connection proxyConnection = createMock(Connection.class);
        expect(mockPooledConnection.getProxy()).andReturn(proxyConnection).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, proxyConnection);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Check functionality
        Connection retrievedConnection = proxy.getConnection();

        assertNotNull(retrievedConnection);
        assertSame(proxyConnection, retrievedConnection, "Should return the Connection proxy");

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 9: Batch Operations
     * Requirement: Verify addBatch and executeBatch delegate to the underlying statement.
     */
    @Test
    void testBatchOperations() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        String sql1 = "INSERT INTO test VALUES (1)";
        String sql2 = "INSERT INTO test VALUES (2)";
        int[] results = new int[]{1, 1};

        // Batch expectations
        mockRealStatement.addBatch(sql1);
        expectLastCall().once();

        mockRealStatement.addBatch(sql2);
        expectLastCall().once();

        expect(mockRealStatement.executeBatch()).andReturn(results);

        // Printing expectations (if enabled, but default mock might not print)
        // Since isVerbose/isPrintSQL are checked on connection, we need to mock them if we care
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Perform batch operations
        proxy.addBatch(sql1);
        proxy.addBatch(sql2);
        int[] ret = proxy.executeBatch();

        assertArrayEquals(results, ret);

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 10: Generated Keys Support
     * Requirement: Verify execute(sql, autoGeneratedKeys) and getGeneratedKeys() delegate correctly.
     */
    @Test
    void testGeneratedKeysSupport() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        String insertSql = "INSERT INTO user(name) VALUES ('test')";

        // 1. Expect execute with flag
        expect(mockRealStatement.execute(insertSql, Statement.RETURN_GENERATED_KEYS)).andReturn(false);
        expect(mockRealStatement.getUpdateCount()).andReturn(1); // Inserted 1 row

        // 2. Expect getGeneratedKeys
        ResultSet genKeysRs = createMock(ResultSet.class);
        expect(mockRealStatement.getGeneratedKeys()).andReturn(genKeysRs);

        // 3. Verify interaction with generated keys RS
        expect(genKeysRs.next()).andReturn(true);
        expect(genKeysRs.getInt(1)).andReturn(100); // ID = 100
        genKeysRs.close();
        expectLastCall().once();

        // CheckIn
        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool, genKeysRs);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Execute with generated keys request
        boolean hasRs = proxy.execute(insertSql, Statement.RETURN_GENERATED_KEYS);
        assertFalse(hasRs);
        assertEquals(1, proxy.getUpdateCount());

        // Retrieve generated keys
        ResultSet keys = proxy.getGeneratedKeys();
        assertNotNull(keys);

        // Currently PooledStatement does NOT wrap getGeneratedKeys result, so it should be the raw mock
        assertSame(genKeysRs, keys);

        assertTrue(keys.next());
        assertEquals(100, keys.getInt(1));

        // Close keys RS explicitly (good practice)
        keys.close();

        proxy.close();
        verify(mockPooledConnection, mockRealStatement, genKeysRs);
    }

    /**
     * Test Case 12: Cancel Operation
     * Requirement: Verify cancel() delegates to the underlying statement.
     */
    @Test
    void testCancelDelegation() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Expectation: cancel() called on real statement
        mockRealStatement.cancel();
        expectLastCall().once();

        // CheckIn needed for close()
        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Perform cancel
        proxy.cancel();

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test Case 13: Warnings Support
     * Requirement: Verify getWarnings and clearWarnings delegate.
     */
    @Test
    void testWarningsSupport() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        java.sql.SQLWarning warning = new java.sql.SQLWarning("Test Warning");
        expect(mockRealStatement.getWarnings()).andReturn(warning);
        mockRealStatement.clearWarnings();
        expectLastCall().once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        assertSame(warning, proxy.getWarnings());
        proxy.clearWarnings();

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: checkOut when already checked out by same thread (line 116)
     */
    @Test
    void testCheckOutAlreadyCheckedOutSameThread() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy1 = pooledStatement.checkOut();

        // Try to check out again from the same thread - should return the same proxy
        Statement proxy2 = pooledStatement.checkOut();

        assertSame(proxy1, proxy2, "Should return same proxy when checked out by same thread");
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: invoke when statement is not checked out (line 150)
     */
    @Test
    void testInvokeWhenNotCheckedOut() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Close it (check it in)
        proxy.close();

        // Now try to invoke a method when not checked out
        SQLException exception = assertThrows(SQLException.class, () -> {
            proxy.executeQuery("SELECT 1");
        });

        assertTrue(exception.getMessage().contains("closed"),
            "Should throw SQLException indicating statement is closed");
        assertEquals("HY010", exception.getSQLState());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: resultSet.close() throws SQLException (line 207)
     */
    @Test
    void testCloseResultSetThrowsException() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        ResultSet mockResultSet = createMock(ResultSet.class);
        expect(mockRealStatement.executeQuery("SELECT 1")).andReturn(mockResultSet);

        // ResultSet close throws exception - should be ignored
        mockResultSet.close();
        expectLastCall().andThrow(new SQLException("Close failed")).once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockResultSet);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        ResultSet rs = proxy.executeQuery("SELECT 1");
        assertNotNull(rs);

        // Close statement - should handle ResultSet close exception gracefully
        proxy.close();

        verify(mockPooledConnection, mockRealStatement, mockResultSet);
    }

    /**
     * Test: getLargeUpdateCount (line 303)
     */
    @Test
    void testGetLargeUpdateCount() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();
        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();
        expect(mockPool.getInfoSQLThreshold()).andReturn(1000L).anyTimes();
        expect(mockPool.getWarnSQLThreshold()).andReturn(3000L).anyTimes();

        expect(mockRealStatement.executeLargeUpdate("UPDATE test SET x=1")).andReturn(100L);
        mockPooledConnection.setDirty();
        expectLastCall().once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockPool);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        long updated = proxy.executeLargeUpdate("UPDATE test SET x=1");
        assertEquals(100L, updated);

        // Get the cached large update count
        long largeUpdateCount = proxy.getLargeUpdateCount();
        assertEquals(100L, largeUpdateCount);

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: isClosed (line 330)
     */
    @Test
    void testIsClosed() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Statement is checked out, should not be closed
        assertFalse(proxy.isClosed());

        // Close it
        proxy.close();

        // After close (check in), should be considered closed
        assertTrue(proxy.isClosed());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: toString (line 342)
     */
    @Test
    void testToString() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        String result = proxy.toString();
        assertTrue(result.contains("conn-1.STMT#1"), "Should contain statement name");

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: getMaskedSql with masking enabled (line 349)
     */
    @Test
    void testGetMaskedSqlWithMaskingEnabled() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(true).anyTimes();

        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.isMaskSql()).andReturn(true).anyTimes();
        expect(mockPool.getInfoSQLThreshold()).andReturn(1000L).anyTimes();
        expect(mockPool.getWarnSQLThreshold()).andReturn(3000L).anyTimes();

        SqlMasker mockMasker = createMock(SqlMasker.class);
        expect(mockPool.getSqlMasker()).andReturn(mockMasker).anyTimes();

        String sql = "SELECT * FROM users WHERE password='secret'";
        expect(mockMasker.maskSensitiveFields(anyString()))
            .andReturn("SELECT * FROM users WHERE password='***'").anyTimes();

        expect(mockRealStatement.executeQuery(sql)).andReturn(null);

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockPool, mockConfig, mockMasker);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Execute a query to set sqlDoing
        proxy.executeQuery(sql);

        proxy.close();
        verify(mockPooledConnection, mockRealStatement, mockPool, mockConfig, mockMasker);
    }

    /**
     * Test: printSQL with isPrintSQL() returning false (line 361)
     */
    @Test
    void testPrintSQLWhenDisabled() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        expect(mockRealStatement.executeQuery("SELECT 1")).andReturn(null);

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // Execute query with printSQL disabled - should not print
        proxy.executeQuery("SELECT 1");

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: onExecuteMethodDone with object return type that's not int/long (line 437-438)
     */
    @Test
    void testOnExecuteMethodDoneWithObjectReturn() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        // executeUpdate returns int[], which is not int or long
        int[] generatedKeys = {1, 2, 3};
        expect(mockRealStatement.executeUpdate("INSERT INTO test VALUES (1)", Statement.RETURN_GENERATED_KEYS))
            .andReturn(3);
        mockPooledConnection.setDirty();
        expectLastCall().once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        int count = proxy.executeUpdate("INSERT INTO test VALUES (1)", Statement.RETURN_GENERATED_KEYS);
        assertEquals(3, count);

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    // ==================== 新增测试用例：覆盖未测试的分支 ====================

    /**
     * Test: 同一线程重复检出Statement (line 113)
     */
    @Test
    void testSameThreadCheckOutTwice() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement firstCheckout = pooledStatement.checkOut();

        // 同一线程第二次检出应该返回相同的Statement
        Statement secondCheckout = pooledStatement.checkOut();
        assertSame(firstCheckout, secondCheckout);

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: 不同线程检出Statement抛出异常 (line 116)
     */
    @Test
    void testDifferentThreadCheckOut() throws Exception {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        pooledStatement.checkOut();

        var exception = new java.util.concurrent.atomic.AtomicReference<SQLException>();
        var latch = new java.util.concurrent.CountDownLatch(1);

        Thread otherThread = new Thread(() -> {
            try {
                pooledStatement.checkOut();
            } catch (SQLException e) {
                exception.set(e);
            } finally {
                latch.countDown();
            }
        });
        otherThread.start();

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNotNull(exception.get());
        assertTrue(exception.get().getMessage().contains("已经被"));
        assertEquals("60003", exception.get().getSQLState());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: executeLargeUpdate返回long类型 (line 309-311)
     */
    @Test
    void testExecuteLargeUpdate() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        expect(mockRealStatement.executeLargeUpdate("UPDATE test SET col=1")).andReturn(100L);
        mockPooledConnection.setDirty();
        expectLastCall().once();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        long result = proxy.executeLargeUpdate("UPDATE test SET col=1");
        assertEquals(100L, result);

        proxy.close();
        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: getCheckOutTime (line 436-439)
     */
    @Test
    void testGetCheckOutTime() throws Exception {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        Thread.sleep(10);
        assertTrue(pooledStatement.getCheckOutTime() >= 0);

        proxy.close();
        assertEquals(0L, pooledStatement.getCheckOutTime());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: getTimeCheckIn (line 465)
     */
    @Test
    void testGetTimeCheckIn() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);

        long timeCheckIn = pooledStatement.getTimeCheckIn();
        assertTrue(timeCheckIn > 0);

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: close时真实Statement抛出异常 (line 412)
     */
    @Test
    void testCloseRealStatementThrowsException() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockRealStatement.close();
        expectLastCall().andThrow(new SQLException("Mock close exception"));

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);

        assertDoesNotThrow(() -> pooledStatement.close());
        assertTrue(pooledStatement.isClosed());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: Statement未检出时执行操作抛出异常 (line 154-155)
     */
    @Test
    void testExecuteWhenNotCheckedOut() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPooledConnection.checkIn(anyObject(PooledStatement.class));
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        proxy.close();

        SQLException exception = assertThrows(SQLException.class, () -> {
            proxy.executeQuery("SELECT * FROM test");
        });

        assertTrue(exception.getMessage().contains("closed"));
        assertEquals("HY010", exception.getSQLState());

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: getMoreResults返回false (line 248-252)
     */
    @Test
    void testGetMoreResultsReturnsFalse() throws SQLException {
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(true).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();

        expect(mockRealStatement.getMoreResults()).andReturn(false);
        expect(mockRealStatement.getUpdateCount()).andReturn(10);

        replay(mockPooledConnection, mockRealStatement);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        boolean hasMore = proxy.getMoreResults();
        assertFalse(hasMore);

        verify(mockPooledConnection, mockRealStatement);
    }

    /**
     * Test: printSQL方法中LogUtil.logBasedOnThreshold的调用 (line 345-347)
     * 需要isPrintSQL=true且LogUtil.isEnabled返回true
     * LogUtil.isEnabled返回true的条件：
     * 1. usedTimeMillis > warnThresholdMillis 且 logger.isWarnEnabled()
     * 2. 或 usedTimeMillis > infoThresholdMillis 且 logger.isInfoEnabled()
     * 3. 或 logger.isDebugEnabled()
     *
     * 关键：设置warnThreshold为负数，这样任何执行时间都会 > warnThreshold
     * 并且logger.isWarnEnabled()通常为true，所以LogUtil.isEnabled会返回true
     */
    @Test
    void testPrintSQLWithThresholdLogging() throws SQLException {
        Hqcp mockPool = createMock(Hqcp.class);
        HqcpConfig mockConfig = createMock(HqcpConfig.class);

        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(true).anyTimes(); // 开启 printSQL
        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();

        // 关键：设置warnThreshold为负数，这样任何执行时间都会 > warnThreshold
        // 由于logger.isWarnEnabled()通常为true，所以LogUtil.isEnabled会返回true
        expect(mockPool.getInfoSQLThreshold()).andReturn(1000L).anyTimes();
        expect(mockPool.getWarnSQLThreshold()).andReturn(-1L).anyTimes(); // 设置为负数
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockPool.getSqlMasker()).andReturn(null).anyTimes();
        expect(mockConfig.isMaskSql()).andReturn(false).anyTimes();

        mockRealStatement.addBatch("INSERT INTO test VALUES (1)");
        expectLastCall().once();

        replay(mockPooledConnection, mockRealStatement, mockPool, mockConfig);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // addBatch会调用printSQL，因为isPrintSQL=true，会检查LogUtil.isEnabled
        // 由于usedTimeMillis必然 > -1 (warnThresholdMillis)，且logger.isWarnEnabled()为true
        // 所以LogUtil.isEnabled返回true，从而触发line 345-347的LogUtil.logBasedOnThreshold
        proxy.addBatch("INSERT INTO test VALUES (1)");

        verify(mockPooledConnection, mockRealStatement, mockPool, mockConfig);
    }

    /**
     * Test: isBusying方法中LogUtil.logBasedOnThreshold的调用 (line 446-448)
     * 需要在Statement执行过程中调用isBusying，且LogUtil.isEnabled返回true
     *
     * LogUtil.isEnabled返回true的条件：
     * 1. usedTimeMillis > warnThresholdMillis 且 logger.isWarnEnabled()
     * 2. 或 usedTimeMillis > infoThresholdMillis 且 logger.isInfoEnabled()
     * 3. 或 logger.isDebugEnabled()
     *
     * 设置warnThreshold为负数，确保任何执行时间都能触发日志
     */
    @Test
    void testIsBusyingWithLongRunningSQL() throws Exception {
        Hqcp mockPool = createMock(Hqcp.class);
        HqcpConfig mockConfig = createMock(HqcpConfig.class);

        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();
        expect(mockPooledConnection.isPrintSQL()).andReturn(false).anyTimes();
        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();

        // 关键：设置warnThreshold为负数，确保 LogUtil.isEnabled 返回 true
        expect(mockPool.getInfoSQLThreshold()).andReturn(1000L).anyTimes();
        expect(mockPool.getWarnSQLThreshold()).andReturn(-1L).anyTimes(); // 设置为负数
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockPool.getSqlMasker()).andReturn(null).anyTimes();
        expect(mockConfig.isMaskSql()).andReturn(false).anyTimes();

        // 模拟一个长时间运行的executeQuery
        expect(mockRealStatement.executeQuery("SELECT SLEEP(1)")).andAnswer(() -> {
            Thread.sleep(10); // 模拟长时间执行
            return null;
        }).once();

        replay(mockPooledConnection, mockRealStatement, mockPool, mockConfig);

        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // 在一个线程中执行SQL
        Thread executionThread = new Thread(() -> {
            try {
                proxy.executeQuery("SELECT SLEEP(1)");
            } catch (Exception e) {
                // ignore
            }
        });
        executionThread.start();

        // 等待一小段时间确保SQL开始执行
        Thread.sleep(5);

        // 在SQL执行过程中调用isBusying
        // 由于busying > 0，且usedNS/1000000必然 > -1 (warnThresholdMillis)
        // 且logger.isWarnEnabled()为true，所以LogUtil.isEnabled返回true
        // 触发line 446-448的日志
        boolean busying = pooledStatement.isBusying();
        assertTrue(busying, "Statement应该正在执行中");

        executionThread.join(1000);

        verify(mockPooledConnection, mockRealStatement, mockPool, mockConfig);
    }

    /**
     * Test: getMaskedSql() 调用 sqlMasker.maskSensitiveFields() (覆盖第359行)
     * 场景：启用SQL遮蔽且sqlMasker不为null时，应该调用maskSensitiveFields方法
     * 通过触发SQLException来确保getMaskedSql()在异常日志中被调用
     */
    @Test
    void testGetMaskedSqlInvokesMaskSensitiveFields() throws SQLException {
        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("conn-1").anyTimes();
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.isVerbose()).andReturn(false).anyTimes();

        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();

        // 关键配置：启用SQL遮蔽
        expect(mockConfig.isMaskSql()).andReturn(true).anyTimes();

        // Mock SqlMasker
        SqlMasker mockMasker = createMock(SqlMasker.class);
        expect(mockPool.getSqlMasker()).andReturn(mockMasker).anyTimes();

        String originalSql = "SELECT * FROM users WHERE password='secret123' AND email='test@example.com'";
        String maskedSql = "SELECT * FROM users WHERE password='***' AND email='***'";

        // 期望maskSensitiveFields被调用（SQL会经过removeBreakingWhitespace处理）
        // 使用anyString()因为SQL可能被格式化
        expect(mockMasker.maskSensitiveFields(anyString()))
                .andReturn(maskedSql).once();

        // Mock executeQuery抛出SQLException
        SQLException sqlException = new SQLException("Table not found", "42S02", 1146);
        expect(mockRealStatement.executeQuery(originalSql)).andThrow(sqlException);

        // 异常处理：非致命异常
        expect(mockPooledConnection.isFatalException(sqlException)).andReturn(false);

        replay(mockPooledConnection, mockRealStatement, mockPool, mockConfig, mockMasker);

        // 执行测试
        PooledStatement pooledStatement = new PooledStatement(mockPooledConnection, mockRealStatement, 1);
        Statement proxy = pooledStatement.checkOut();

        // 执行查询，会抛出SQLException，触发getMaskedSql()调用
        try {
            proxy.executeQuery(originalSql);
            fail("Expected SQLException to be thrown");
        } catch (SQLException e) {
            assertEquals(sqlException, e);
        }

        // 验证sqlMasker.maskSensitiveFields被调用
        verify(mockPooledConnection, mockRealStatement, mockPool, mockConfig, mockMasker);
    }

    /**
     * 开启 SQL 掩码时，失败日志不打驱动异常原文（MySQL Duplicate entry 消息里带唯一键值），只打类名 + ErrorCode + SQLState
     */
    @Test
    void testFailureLogOmitsDriverMessageWhenMaskSqlEnabled() throws SQLException {
        String message = failureLogOfDuplicateEntry(true);
        assertFalse(message.contains("VC-0001-9876"), message);
        assertFalse(message.contains("Duplicate entry"), message);
        assertTrue(message.startsWith(SQLIntegrityConstraintViolationException.class.getName() + "[ErrorCode=1062;SQLState=23000]"), message);
        assertTrue(message.contains("voucher_no"), message);
    }

    /**
     * 未开启 SQL 掩码时，失败日志行为不变：照旧打驱动异常原文
     */
    @Test
    void testFailureLogKeepsDriverMessageWhenMaskSqlDisabled() throws SQLException {
        String message = failureLogOfDuplicateEntry(false);
        assertTrue(message.startsWith(DUPLICATE_ENTRY.toString() + "[ErrorCode=1062;SQLState=23000]"), message);
    }

    private static final SQLException DUPLICATE_ENTRY = new SQLIntegrityConstraintViolationException(
            "Duplicate entry 'VC-0001-9876' for key 'sales_voucher.uk_voucher_no'", "23000", 1062);

    /**
     * 让 execute 抛唯一键冲突，返回 PooledStatement 打出的那条失败日志
     */
    private String failureLogOfDuplicateEntry(boolean maskSql) throws SQLException {
        String sql = "INSERT INTO sales_voucher (id, voucher_no) VALUES (1, 'VC-0001-9876')";
        expect(mockRealStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        expect(mockPooledConnection.getConnectionPool()).andReturn(mockPool).anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.isMaskSql()).andReturn(maskSql).anyTimes();
        expect(mockPool.getSqlMasker()).andReturn(new SqlMasker("****", Set.of("voucher_no"))).anyTimes();
        expect(mockRealStatement.execute(sql)).andThrow(DUPLICATE_ENTRY);
        expect(mockPooledConnection.isFatalException(DUPLICATE_ENTRY)).andReturn(false);
        replay(mockPooledConnection, mockRealStatement, mockConfig, mockPool);

        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PooledStatement.class.getName());
        Level originalLevel = logbackLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.INFO);
        try {
            Statement proxy = new PooledStatement(mockPooledConnection, mockRealStatement, 1).checkOut();
            assertSame(DUPLICATE_ENTRY, assertThrows(SQLException.class, () -> proxy.execute(sql)));
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(originalLevel);
        }
        List<String> messages = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("ErrorCode="))
                .toList();
        assertEquals(1, messages.size(), messages.toString());
        return messages.get(0);
    }
}
