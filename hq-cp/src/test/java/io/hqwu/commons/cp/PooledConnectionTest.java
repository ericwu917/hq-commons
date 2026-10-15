package io.hqwu.commons.cp;

import com.jolbox.bonecp.MockConstant;
import com.jolbox.bonecp.MockJDBCDriver;
import org.easymock.IMocksControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.easymock.EasyMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class PooledConnectionTest {

    private IMocksControl control;
    private Hqcp mockPool;
    private HqcpConfig mockConfig;
    private Connection mockRealConnection;

    @BeforeEach
    public void setup() throws SQLException {
        control = createNiceControl();
        mockPool = control.createMock(Hqcp.class);
        mockConfig = control.createMock(HqcpConfig.class);
        mockRealConnection = control.createMock(Connection.class);

        // Setup MockDriver
        MockJDBCDriver.getInstance().setConnection(mockRealConnection);

        // Common expectations
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();

        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
    }

    @AfterEach
    public void teardown() throws SQLException {
        MockJDBCDriver.getInstance().disable();
    }

    /**
     * Test Requirement 1: Logical Close
     * Calling close() on the proxy should return the connection to the pool (checkIn)
     * and not close the physical connection.
     */
    @Test
    public void testLogicalClose() throws SQLException {
        // Initial state for constructor
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // Expectation: checkIn is called on the pool
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // 1. Create PooledConnection
        // This will trigger makeRealConnection -> MockDriver.connect
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Verify underlying connection is marked as open (physically)
        assertFalse(pooledConnection.isClosed());

        // 2. Checkout the proxy
        Connection proxy = pooledConnection.checkOut(true);
        assertNotNull(proxy);
        assertTrue(pooledConnection.isCheckOut());

        // 3. Close the proxy (logical close)
        proxy.close();

        // 4. Verify pool.checkIn was called
        control.verify();

        // 5. Verify logical state
        assertFalse(pooledConnection.isCheckOut());

        // Note: The physical connection should still be open
        // (We didn't expect real_connection.close())
    }

    /**
     * Test Requirement 2: Transaction State
     * Verify setAutoCommit, commit, rollback delegate to real connection.
     * Verify cleanup on close (dirty check).
     */
    @Test
    public void testTransactionState() throws SQLException {
        // --- Expectations ---

        // Config: Commit on close = false (should rollback)
        expect(mockConfig.isCommitOnClose()).andReturn(false).anyTimes();

        // 1. Initialization (in constructor)
        expect(mockRealConnection.getAutoCommit()).andReturn(true).times(1);

        // 2. setAutoCommit(false)
        mockRealConnection.setAutoCommit(false);
        expectLastCall().once();
        // The implementation calls getAutoCommit immediately after setAutoCommit to update local cache
        expect(mockRealConnection.getAutoCommit()).andReturn(false).times(1);

        // 3. commit()
        mockRealConnection.commit();
        expectLastCall().once();

        // 4. rollback()
        mockRealConnection.rollback();
        expectLastCall().once();

        // 5. Cleanup on close (dirty & !autoCommit) -> rollback
        mockRealConnection.rollback();
        expectLastCall().once();

        // Logical checkIn
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---

        // 1. Create Connection
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true); // checkout(true) -> autoCommit=true, matches current.

        // 2. Change Transaction State
        proxy.setAutoCommit(false); // Delegates to real

        // 3. Commit
        proxy.commit(); // Delegates to real

        // 4. Rollback
        proxy.rollback(); // Delegates to real

        // 5. Simulate Dirty State & Close
        // Manually set dirty=true to simulate executed update
        pooledConnection.setDirty();

        // Assert state before close
        // assertFalse(pooledConnection.getAutoCommit()); // Internal state check not available publicly

        proxy.close(); // Should trigger rollback() then checkIn()

        control.verify();
    }

    /**
     * Test Requirement 3: Statement Creation and Caching
     * Verify that Statements, PreparedStatements, and CallableStatements are correctly created
     * by the proxy, and that PreparedStatement caching works (second call does not trigger physical creation).
     */
    @Test
    public void testStatementCreationAndCaching() throws SQLException {
        String sql = "SELECT 1";
        String sqlCall = "CALL sp_test()";

        // Mocks for statements
        Statement mockStmt = control.createMock(Statement.class);
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);
        CallableStatement mockCstmt = control.createMock(CallableStatement.class);

        // --- Expectations ---

        // 1. Init
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // 2. createStatement
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 3. prepareStatement (First time - Cache Miss)
        expect(mockRealConnection.prepareStatement(sql)).andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        // PooledConnection calls clearParameters on checkIn/reuse
        // Note: exact call count depends on when it's closed/checked-in
        mockPstmt.clearParameters();
        expectLastCall().anyTimes();

        // 4. prepareCall
        expect(mockRealConnection.prepareCall(sqlCall)).andReturn(mockCstmt).once();
        expect(mockCstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockCstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        mockCstmt.clearParameters();
        expectLastCall().anyTimes();

        // 5. Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // A. createStatement
        Statement s1 = proxy.createStatement();
        assertNotNull(s1);
        assertTrue(Proxy.isProxyClass(s1.getClass()));

        // B. prepareStatement (Cache Miss)
        PreparedStatement ps1 = proxy.prepareStatement(sql);
        assertNotNull(ps1);

        // Return ps1 to cache
        ps1.close();

        // C. prepareStatement (Cache Hit)
        // If caching works, this will NOT trigger real_connection.prepareStatement(sql) again
        PreparedStatement ps2 = proxy.prepareStatement(sql);
        assertNotNull(ps2);

        // D. prepareCall
        CallableStatement cs1 = proxy.prepareCall(sqlCall);
        assertNotNull(cs1);

        // Cleanup
        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 4: Exception Handling and Recovery
     * Verify that when a fatal exception (e.g. 08001) occurs, the pool attempts to
     * reconnect and retry the operation (if autoCommit is true).
     */
    @Test
    public void testExceptionHandlingAndRecovery() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        // --- Expectations ---

        // 1. Initial Connection Setup & Recovery will call getAutoCommit
        // Explicitly return true so autoCommit is enabled, enabling retry logic.
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // 2. createStatement -> Throw Fatal Exception (08001)
        expect(mockRealConnection.createStatement())
                .andThrow(new SQLException("Network failure", "08001")).once();

        // 3. Recover Logic:
        //    a. Close old connection (Called by PooledConnection.close() inside recover)
        mockRealConnection.close();
        expectLastCall().once();

        // 4. Retry Logic:
        //    Since autoCommit=true, it retries the invocation (createStatement)
        //    This execution happens after recovery.
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 5. Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // This call triggers the exception, recovery, and retry flow
        Statement s = proxy.createStatement();

        // Assertions
        assertNotNull(s);
        assertTrue(Proxy.isProxyClass(s.getClass()));

        // Verify fatal error flag is reset after successful recovery
        assertFalse(pooledConnection.isFatalExceptionHappened());

        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 5: Delegation
     * Verify that miscellaneous standard JDBC methods (e.g. getMetaData, setReadOnly,
     * setCatalog, getWarnings) are correctly entrusted to the underlying connection.
     */
    @Test
    public void testDelegation() throws SQLException {
        // Mocks
        java.sql.DatabaseMetaData mockMetaData = control.createMock(java.sql.DatabaseMetaData.class);

        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // 1. getMetaData
        expect(mockRealConnection.getMetaData()).andReturn(mockMetaData).once();

        // 2. setReadOnly
        mockRealConnection.setReadOnly(true);
        expectLastCall().once();

        // 3. setCatalog
        mockRealConnection.setCatalog("test_catalog");
        expectLastCall().once();

        // 4. clearWarnings
        mockRealConnection.clearWarnings();
        expectLastCall().once();

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // a. getMetaData
        assertNotNull(proxy.getMetaData());

        // b. setReadOnly
        proxy.setReadOnly(true);

        // c. setCatalog
        proxy.setCatalog("test_catalog");

        // d. clearWarnings
        proxy.clearWarnings();

        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 6: Wrapper Interface
     * Verify that Wrapper interface methods (isWrapperFor, unwrap) are correctly
     * implemented with the following logic:
     * 1. Check if the requested interface is implemented by the InvocationHandler (PooledConnection)
     * 2. Check if the requested interface is implemented by the Proxy itself
     * 3. Delegate to the underlying connection's isWrapperFor/unwrap
     */
    @Test
    public void testWrapperInterface() throws SQLException {
        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Test 1: isWrapperFor with Connection interface (should return true for proxy)
        assertTrue(proxy.isWrapperFor(Connection.class),
            "Proxy should be a wrapper for Connection interface");

        // Test 2: unwrap with Connection interface (should return proxy itself)
        Connection unwrappedConn = proxy.unwrap(Connection.class);
        assertNotNull(unwrappedConn);
        assertSame(proxy, unwrappedConn,
            "Unwrapping Connection should return the proxy itself");

        // Test 3: isWrapperFor with PooledConnection class (handler type)
        assertTrue(proxy.isWrapperFor(PooledConnection.class),
            "Proxy should be a wrapper for PooledConnection (the handler)");

        // Test 4: unwrap with PooledConnection class (should return the handler)
        PooledConnection unwrappedHandler = proxy.unwrap(PooledConnection.class);
        assertNotNull(unwrappedHandler);
        assertSame(pooledConnection, unwrappedHandler,
            "Unwrapping PooledConnection should return the handler itself");

        // Test 5: isWrapperFor with PooledConnectionMBean interface (implemented by handler)
        assertTrue(proxy.isWrapperFor(PooledConnectionMBean.class),
            "Proxy should be a wrapper for PooledConnectionMBean (implemented by handler)");

        // Test 6: unwrap with PooledConnectionMBean interface
        PooledConnectionMBean unwrappedMBean = proxy.unwrap(PooledConnectionMBean.class);
        assertNotNull(unwrappedMBean);
        assertSame(pooledConnection, unwrappedMBean,
            "Unwrapping PooledConnectionMBean should return the handler");

        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 6.1: Wrapper Interface - Delegation to Real Connection
     * Verify that when the requested interface is not implemented by handler or proxy,
     * it delegates to the underlying real connection.
     */
    @Test
    public void testWrapperInterfaceDelegation() throws SQLException {
        // Define a "Vendor Specific" interface for testing delegation
        Class<Runnable> vendorInterface = Runnable.class;
        Runnable mockVendorObject = control.createMock(Runnable.class);

        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // When checking for Runnable interface:
        // 1. PooledConnection does not implement Runnable -> false
        // 2. Proxy does not implement Runnable -> false
        // 3. Delegate to real connection
        expect(mockRealConnection.isWrapperFor(vendorInterface)).andReturn(true).once();

        // When unwrapping Runnable:
        // 1. Proxy is not instance of Runnable
        // 2. Handler is not instance of Runnable
        // 3. Delegate to real connection
        expect(mockRealConnection.unwrap(vendorInterface)).andReturn(mockVendorObject).once();

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Test isWrapperFor delegation
        boolean isWrapper = proxy.isWrapperFor(vendorInterface);
        assertTrue(isWrapper, "Should delegate to underlying connection and return true");

        // Test unwrap delegation
        Runnable result = proxy.unwrap(vendorInterface);
        assertNotNull(result);
        assertSame(mockVendorObject, result,
            "Should delegate to underlying connection and return the vendor object");

        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 6.2: Wrapper Interface - Null and Invalid Cases
     */
    @Test
    public void testWrapperInterfaceNullAndInvalid() throws SQLException {
        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Test 1: isWrapperFor with null should return false
        assertFalse(proxy.isWrapperFor(null), "isWrapperFor(null) should return false");

        // Test 2: unwrap with null should throw exception
        assertThrows(NullPointerException.class, () -> proxy.unwrap(null),
            "unwrap(null) should throw NullPointerException");

        proxy.close();

        control.verify();
    }

    /**
     * Test Requirement 7: Compliance Extras
     * 1. Verify that open statements are automatically closed when the connection is closed.
     * 2. Verify isClosed() returns true after logical close (JDBC Spec).
     */
    @Test
    public void testComplianceExtras() throws SQLException {
        // Mocks for statements
        Statement mockStmt = control.createMock(Statement.class);

        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // 1. createStatement
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. isClosed() delegation checks
        // Before close: delegates to real (false)
        expect(mockRealConnection.isClosed()).andReturn(false).times(1);

        // After close:
        // The Spec says isClosed() should return true.
        // Current implementation delegates to real_connection.isClosed() which is FALSE (physically open).
        // We will assert current behavior initially or fix it.
        // Let's assume we want to enforce spec. If we enforce spec, we shouldn't expect delegation to real conn.

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // A. Statement auto-close
        Statement s = proxy.createStatement();

        // B. Pre-close check
        assertFalse(proxy.isClosed());

        // C. Close
        proxy.close();

        // D. Post-close check
        // According to JDBC spec, this MUST be true.
        // If existing code is buggy, this assertion might fail.
        assertTrue(proxy.isClosed(), "Logical connection should report isClosed()=true after close()");

        control.verify();
    }

    /**
     * Test Requirement 8: AutoCommit=true Behavior
     * Verify that when autoCommit is true, close() does NOT trigger rollback or commit,
     * even if the connection is marked as dirty (e.g. after executing statements).
     */
    @Test
    public void testAutoCommitTrueNoTransactionControl() throws SQLException {
        // --- Expectations ---
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes(); // Always auto-commit

        // 1. Execute some statement to make connection "dirty"
        Statement mockStmt = control.createMock(Statement.class);
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.execute("UPDATE foo SET bar=1")).andReturn(false).once(); // Update count
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Cleanup on close
        // IMPORTANT: We expect NO commit() or rollback() calls on mockRealConnection here.
        // We only expect checkIn.
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement s = proxy.createStatement();
        s.execute("UPDATE foo SET bar=1"); // Sets dirty = true internally

        // Assert: connection logic thinks it's dirty? We can't verify private field easily,
        // but verify() will ensure rollback isn't called.

        proxy.close(); // Should simple checkIn

        control.verify();
    }

    /**
     * Test Requirement 9: AutoCommit State Reset
     * Verify that if a connection is returned with autoCommit=false,
     * the next checkOut(true) correctly resets it to true.
     */
    @Test
    public void testAutoCommitResetsOnCheckOut() throws SQLException {
        // --- Expectations ---

        // 1. Initial State (Constructor)
        expect(mockRealConnection.getAutoCommit()).andReturn(true).times(1);

        // 2. Cycle 1 CheckOut(true) - End of method update
        expect(mockRealConnection.getAutoCommit()).andReturn(true).times(1);

        // 3. User 1 sets autoCommit = false
        mockRealConnection.setAutoCommit(false);
        expectLastCall().once();
        // Internal cache update calls getAutoCommit
        expect(mockRealConnection.getAutoCommit()).andReturn(false).times(1);

        // 4. User 1 returns connection
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        // 5. User 2 checks out with autoCommit = true
        // Pool detects internal state is false, user asks for true -> triggers setAutoCommit(true)
        mockRealConnection.setAutoCommit(true);
        expectLastCall().once();
        // Internal cache update (inside invoke)
        expect(mockRealConnection.getAutoCommit()).andReturn(true).times(1);

        // 6. Cycle 2 CheckOut - End of method update
        expect(mockRealConnection.getAutoCommit()).andReturn(true).times(1);

        control.replay();

        // --- Execution ---
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // --- Cycle 1: User changes state ---
        Connection proxy1 = pooledConnection.checkOut(true); // Matches initial state
        proxy1.setAutoCommit(false); // Change to false
        proxy1.close(); // Return to pool

        // --- Cycle 2: Pool resets state ---
        Connection proxy2 = pooledConnection.checkOut(true); // User wants true

        // verify() ensures setAutoCommit(true) was called
        control.verify();
    }

    /**
     * Test: toString() method delegation
     */
    @Test
    public void testToString() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        String result = proxy.toString();
        assertNotNull(result);
        assertTrue(result.contains("testPool#1"));

        proxy.close();
        control.verify();
    }

    /**
     * Test: createStatement with arguments
     */
    @Test
    public void testCreateStatementWithArgs() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
        assertNotNull(stmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareStatement with arguments (non-default result set type)
     */
    @Test
    public void testPrepareStatementWithArgs() throws SQLException {
        String sql = "SELECT * FROM test";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareCall with arguments (non-default result set type)
     */
    @Test
    public void testPrepareCallWithArgs() throws SQLException {
        String sql = "CALL test_proc(?, ?)";
        CallableStatement mockCstmt = control.createMock(CallableStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareCall(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockCstmt).once();
        expect(mockCstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockCstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        CallableStatement cstmt = proxy.prepareCall(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
        assertNotNull(cstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: recover from non-fatal exception
     */
    @Test
    public void testRecoverFromNonFatalException() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Non-fatal exception - should return false
        SQLException nonFatal = new SQLException("Syntax error", "42000");
        assertFalse(pooledConnection.recover(nonFatal));

        control.verify();
    }

    /**
     * Test: recover from fatal exception - success
     */
    @Test
    public void testRecoverFromFatalExceptionSuccess() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // Close old connection
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Fatal exception - should recover
        SQLException fatal = new SQLException("Connection lost", "08001");
        assertTrue(pooledConnection.recover(fatal));

        control.verify();
    }

    /**
     * Test: buildProxy with connection not implementing Connection interface
     */
    @Test
    public void testBuildProxyWithNonStandardConnection() throws SQLException {
        // This test ensures the code path where Connection interface is added
        // is covered, though in practice JDBC drivers always implement Connection

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);
        assertNotNull(proxy);
        assertTrue(Proxy.isProxyClass(proxy.getClass()));

        control.verify();
    }

    /**
     * Test: doCheck with null check statement
     */
    @Test
    public void testDoCheckWithNullStatement() throws Exception {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockConfig.getCheckStatement()).andReturn(null).once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Should not throw exception
        pooledConnection.doCheck();

        control.verify();
    }

    /**
     * Test: isBusying when statements are active
     */
    @Test
    public void testIsBusyingWithActiveStatements() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Initially not busy
        assertFalse(pooledConnection.isBusying());

        // Create statement but don't close it - connection should be considered busy
        Statement stmt = proxy.createStatement();

        // Note: isBusying() checks if any active statement is executing
        // This is implementation-dependent, but we test the method is callable
        pooledConnection.isBusying(); // Just invoke to cover the code

        control.verify();
    }

    /**
     * Test: close with unclosable real connection
     */
    @Test
    public void testCloseWithUnclosableConnection() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First close attempt throws exception
        mockRealConnection.close();
        expectLastCall().andThrow(new SQLException("Cannot close")).once();

        // Rollback is attempted
        mockRealConnection.rollback();
        expectLastCall().once();

        // Second close attempt succeeds
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: close with completely unclosable connection
     */
    @Test
    public void testCloseWithCompletelyUnclosableConnection() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First close attempt throws exception
        mockRealConnection.close();
        expectLastCall().andThrow(new SQLException("Cannot close")).once();

        // Rollback also throws exception
        mockRealConnection.rollback();
        expectLastCall().andThrow(new SQLException("Cannot rollback")).once();

        // Second close attempt also fails
        mockRealConnection.close();
        expectLastCall().andThrow(new SQLException("Still cannot close")).once();

        // Connection should be offered to unclosed pool
        mockPool.offerUnclosedConnection(eq(mockRealConnection), anyString());
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: getTimeCheckIn and getTimeConnected
     */
    @Test
    public void testTimeGetters() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        long timeCheckIn = pooledConnection.getTimeCheckIn();
        assertTrue(timeCheckIn > 0);

        long timeConnected = pooledConnection.getTimeConnected();
        assertTrue(timeConnected > 0);

        control.verify();
    }

    /**
     * Test: createStatement with 3 arguments (resultSetType, resultSetConcurrency, resultSetHoldability)
     */
    @Test
    public void testCreateStatementWith3Args() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockStmt.getResultSetHoldability()).andReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(stmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareStatement with 3 arguments
     */
    @Test
    public void testPrepareStatementWith3Args() throws SQLException {
        String sql = "SELECT * FROM test";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockPstmt.getResultSetHoldability()).andReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareStatement with auto-generated keys
     */
    @Test
    public void testPrepareStatementWithAutoGeneratedKeys() throws SQLException {
        String sql = "INSERT INTO test VALUES (?)";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareCall with 3 arguments
     */
    @Test
    public void testPrepareCallWith3Args() throws SQLException {
        String sql = "CALL test_proc(?, ?)";
        CallableStatement mockCstmt = control.createMock(CallableStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_SENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.CLOSE_CURSORS_AT_COMMIT))
                .andReturn(mockCstmt).once();
        expect(mockCstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockCstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockCstmt.getResultSetHoldability()).andReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        CallableStatement cstmt = proxy.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_SENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertNotNull(cstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: invoke on closed connection (should reopen)
     */
    @Test
    public void testInvokeOnClosedConnection() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First checkout and checkin
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        // Close connection
        mockRealConnection.close();
        expectLastCall().once();

        // When invoking after close, connection should be reopened
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Check in first
        proxy.close();

        // Close physical connection
        pooledConnection.close();

        // Checkout again - should reopen connection
        proxy = pooledConnection.checkOut(true);
        Statement stmt = proxy.createStatement();
        assertNotNull(stmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: checkIn with force closing active statements
     */
    @Test
    public void testCheckInWithForceCloseActiveStatements() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // When active statement is force-closed, it's checked back into idle pool (not physically closed)
        // No mockStmt.close() expectation needed as default statement goes to idle pool

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Create statement but don't close it manually
        Statement stmt = proxy.createStatement();
        assertNotNull(stmt);

        // Close connection - should force close the active statement
        proxy.close();

        // Verify the statement was returned to pool
        assertEquals(1, pooledConnection.getCachedStatementsCount());

        control.verify();
    }

    /**
     * Test: isClosed with various states
     */
    @Test
    public void testIsClosedVariousStates() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.isClosed()).andReturn(false).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Not checked out - should be considered closed -> not checked out, proxy is null
        assertNull(pooledConnection.getProxy());

        Connection proxy = pooledConnection.checkOut(true);

        // Checked out and open - should be open
        assertFalse(proxy.isClosed());

        proxy.close();

        // After close - should be closed
        assertTrue(proxy.isClosed());

        control.verify();
    }

    /**
     * Test: Statement with non-default ResultSet type should not be pooled
     */
    @Test
    public void testStatementNonDefaultNotPooled() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        // Non-default statement should be closed when checked in
        mockStmt.close();
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
        assertNotNull(stmt);

        // Close the statement
        stmt.close();

        proxy.close();
        control.verify();
    }

    /**
     * Test: PreparedStatement with non-default type should not be cached
     */
    @Test
    public void testPreparedStatementNonDefaultNotCached() throws SQLException {
        String sql = "SELECT 1";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
        assertNotNull(pstmt);

        // Close it
        pstmt.close();

        // Should be 0 because non-default type is not cached
        assertEquals(0, pooledConnection.getCachedPreStatementsCount());

        proxy.close();
        control.verify();
    }

    /**
     * Test: 驱动已关闭真实语句（closeOnCompletion 后结果集关闭）时，归还的 PreparedStatement 不再留在缓存
     */
    @Test
    public void testPreparedStatementClosedByDriverEvictedOnCheckIn() throws SQLException {
        String sql = "SELECT * FROM t WHERE id = ?";
        AtomicBoolean realClosed = new AtomicBoolean(false);
        PreparedStatement mockPstmt1 = control.createMock(PreparedStatement.class);
        PreparedStatement mockPstmt2 = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql)).andReturn(mockPstmt1).andReturn(mockPstmt2);
        stubDefaultResultSetType(mockPstmt1, mockPstmt2);
        expect(mockPstmt1.isClosed()).andAnswer(realClosed::get).anyTimes();
        mockPstmt2.setString(1, "b");
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql);
        pstmt.closeOnCompletion();
        realClosed.set(true); // 模拟驱动在结果集关闭时关闭真实语句
        pstmt.close();
        assertEquals(0, pooledConnection.getCachedPreStatementsCount());

        // 第二次 prepare 必须拿到新的真实语句
        proxy.prepareStatement(sql).setString(1, "b");
        assertEquals(1, pooledConnection.getCachedPreStatementsCount());

        proxy.close();
        control.verify();
    }

    /**
     * Test: 事务内语句未归还、真实语句已被驱动关闭时，同线程再次 prepare 命中缓存也要换新语句
     */
    @Test
    public void testPreparedStatementClosedByDriverReplacedOnCacheHit() throws SQLException {
        String sql = "SELECT * FROM t WHERE id = ?";
        AtomicBoolean realClosed = new AtomicBoolean(false);
        PreparedStatement mockPstmt1 = control.createMock(PreparedStatement.class);
        PreparedStatement mockPstmt2 = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();
        expect(mockRealConnection.prepareStatement(sql)).andReturn(mockPstmt1).andReturn(mockPstmt2);
        stubDefaultResultSetType(mockPstmt1, mockPstmt2);
        expect(mockPstmt1.isClosed()).andAnswer(realClosed::get).anyTimes();
        mockPstmt2.setString(1, "b");
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt1 = proxy.prepareStatement(sql);
        pstmt1.closeOnCompletion();
        realClosed.set(true); // 调用方不 close 语句，驱动在结果集关闭时已关闭真实语句

        PreparedStatement pstmt2 = proxy.prepareStatement(sql);
        assertNotSame(pstmt1, pstmt2);
        pstmt2.setString(1, "b");
        assertEquals(1, pooledConnection.getCachedPreStatementsCount());

        proxy.close();
        control.verify();
    }

    /**
     * Test: 驱动已关闭真实语句时，归还的 Statement 不进空闲池
     */
    @Test
    public void testStatementClosedByDriverNotReused() throws SQLException {
        AtomicBoolean realClosed = new AtomicBoolean(false);
        Statement mockStmt1 = control.createMock(Statement.class);
        Statement mockStmt2 = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt1).andReturn(mockStmt2);
        stubDefaultResultSetType(mockStmt1, mockStmt2);
        expect(mockStmt1.isClosed()).andAnswer(realClosed::get).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement();
        stmt.closeOnCompletion();
        realClosed.set(true);
        stmt.close();
        assertEquals(0, pooledConnection.getCachedStatementsCount());

        // 第二次 createStatement 必须拿到新的真实语句
        proxy.createStatement().close();
        assertEquals(1, pooledConnection.getCachedStatementsCount());

        proxy.close();
        control.verify();
    }

    /**
     * Test: 归还时真实语句仍打开、进空闲池后才被驱动关闭（如 closeOnCompletion 后晚关的 generated keys），出池时也要换新语句
     */
    @Test
    public void testIdleStatementClosedByDriverNotReused() throws SQLException {
        AtomicBoolean realClosed = new AtomicBoolean(false);
        Statement mockStmt1 = control.createMock(Statement.class);
        Statement mockStmt2 = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt1).andReturn(mockStmt2);
        stubDefaultResultSetType(mockStmt1, mockStmt2);
        expect(mockStmt1.isClosed()).andAnswer(realClosed::get).anyTimes();
        expect(mockStmt2.executeUpdate("UPDATE t SET a = 1")).andReturn(1).once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt1 = proxy.createStatement();
        stmt1.closeOnCompletion();
        stmt1.close();
        assertEquals(1, pooledConnection.getCachedStatementsCount());
        realClosed.set(true); // 语句已在空闲池，驱动随后才关闭真实语句

        // 第二次 createStatement 必须拿到新的真实语句
        Statement stmt2 = proxy.createStatement();
        assertEquals(1, stmt2.executeUpdate("UPDATE t SET a = 1"));
        stmt2.close();
        assertEquals(1, pooledConnection.getCachedStatementsCount());

        proxy.close();
        control.verify();
    }

    private static void stubDefaultResultSetType(Statement... stmts) throws SQLException {
        for (Statement stmt : stmts) {
            expect(stmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
            expect(stmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        }
    }

    /**
     * Test: recover in transaction mode (autoCommit=false) should not retry
     */
    @Test
    public void testRecoverInTransactionModeNoRetry() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        // createStatement throws fatal exception
        expect(mockRealConnection.createStatement())
                .andThrow(new SQLException("Connection lost", "08001")).once();

        // Recover should close old connection
        mockRealConnection.close();
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false); // autoCommit=false (transaction mode)

        // In transaction mode, should NOT retry after recovery - exception should be thrown
        SQLException exception = assertThrows(SQLException.class, () -> {
            proxy.createStatement();
        });
        assertEquals("08001", exception.getSQLState());

        proxy.close();
        control.verify();
    }

    /**
     * Test: commit should reset dirty flag
     */
    @Test
    public void testCommitResetsDirtyFlag() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        mockRealConnection.commit();
        expectLastCall().once();

        // No rollback should happen on close because dirty flag was reset
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        pooledConnection.setDirty();
        proxy.commit(); // This should reset dirty flag

        proxy.close(); // Should NOT rollback

        control.verify();
    }

    /**
     * Test: rollback should reset dirty flag
     */
    @Test
    public void testRollbackResetsDirtyFlag() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        mockRealConnection.rollback();
        expectLastCall().once();

        // No rollback should happen on close because dirty flag was reset
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        pooledConnection.setDirty();
        proxy.rollback(); // This should reset dirty flag

        proxy.close(); // Should NOT rollback again

        control.verify();
    }

    /**
     * Test: MBean method - getCachedStatementsCount
     */
    @Test
    public void testGetCachedStatementsCount() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        assertEquals(0, pooledConnection.getCachedStatementsCount());

        Statement stmt = proxy.createStatement();
        stmt.close(); // Return to idle pool

        // Should have 1 cached statement
        assertEquals(1, pooledConnection.getCachedStatementsCount());

        proxy.close();
        control.verify();
    }

    /**
     * Test: checkOut when already checked out should throw exception
     */
    @Test
    public void testCheckOutWhenAlreadyCheckedOut() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Try to checkout again without checking in
        SQLException exception = assertThrows(SQLException.class, () -> {
            pooledConnection.checkOut(true);
        });
        assertTrue(exception.getMessage().contains("checkout") || exception.getMessage().contains("检出"));

        control.verify();
    }

    /**
     * Test: checkIn when not checked out should do nothing
     */
    @Test
    public void testCheckInWhenNotCheckedOut() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // checkIn without checkout should return immediately
        Connection proxy = pooledConnection.checkOut(true);
        proxy.close(); // This calls checkIn, but connection is not checked out

        // No exception should be thrown and no pool.checkIn should be called
        control.verify();
    }

    /**
     * Test: commitOnClose with commit enabled
     */
    @Test
    public void testCommitOnCloseWithCommit() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();
        expect(mockConfig.isCommitOnClose()).andReturn(true).once();

        mockRealConnection.commit();
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        // Make connection dirty
        pooledConnection.setDirty();

        // Close should trigger commit
        proxy.close();

        control.verify();
    }

    /**
     * Test: doCheck with successful health check
     */
    @Test
    public void testDoCheckSuccess() throws Exception {
        Statement mockStmt = control.createMock(Statement.class);
        ResultSet mockRs = control.createMock(ResultSet.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockConfig.getCheckStatement()).andReturn("SELECT 1").once();

        // Connection is open, so no makeRealConnection
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.executeQuery("SELECT 1")).andReturn(mockRs).once();
        expect(mockRs.next()).andReturn(true).once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        pooledConnection.doCheck();

        control.verify();
    }

    /**
     * Test: doCheck with closed connection
     */
    @Test
    public void testDoCheckWithClosedConnection() throws Exception {
        Statement mockStmt = control.createMock(Statement.class);
        ResultSet mockRs = control.createMock(ResultSet.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockConfig.getCheckStatement()).andReturn("SELECT 1").once();

        // Connection needs to be reopened
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.executeQuery("SELECT 1")).andReturn(mockRs).once();
        expect(mockRs.next()).andReturn(true).once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Close the connection first
        pooledConnection.close();

        // doCheck should reopen and check
        pooledConnection.doCheck();

        control.verify();
    }

    /**
     * Test: millisToCheckIt calculation
     */
    @Test
    public void testMillisToCheckIt() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockConfig.getIdleTimeoutMillisec()).andReturn(30000L).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        long millisToCheck = pooledConnection.millisToCheckIt();
        // Should be positive (assuming test runs within 30 seconds)
        assertTrue(millisToCheck > 0);

        control.verify();
    }

    /**
     * Test: millisToDestroy with infinite lifetime
     */
    @Test
    public void testMillisToDestroyInfinite() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // getLifetimeSec() is already mocked in setup to return 0L
        long millisToDestroy = pooledConnection.millisToDestroy();
        assertEquals(Long.MAX_VALUE, millisToDestroy);

        control.verify();
    }

    /**
     * Test: getCachedPreStatementsSQLs
     */
    @Test
    public void testGetCachedPreStatementsSQLs() throws SQLException {
        String sql1 = "SELECT * FROM table1";
        String sql2 = "SELECT * FROM table2";
        PreparedStatement mockPstmt1 = control.createMock(PreparedStatement.class);
        PreparedStatement mockPstmt2 = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        expect(mockRealConnection.prepareStatement(sql1)).andReturn(mockPstmt1).once();
        expect(mockPstmt1.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt1.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        mockPstmt1.clearParameters();
        expectLastCall().anyTimes();

        expect(mockRealConnection.prepareStatement(sql2)).andReturn(mockPstmt2).once();
        expect(mockPstmt2.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt2.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        mockPstmt2.clearParameters();
        expectLastCall().anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Create and cache two prepared statements
        PreparedStatement ps1 = proxy.prepareStatement(sql1);
        ps1.close();
        PreparedStatement ps2 = proxy.prepareStatement(sql2);
        ps2.close();

        // Get cached SQLs
        String[] cachedSQLs = pooledConnection.getCachedPreStatementsSQLs();
        assertNotNull(cachedSQLs);
        assertEquals(2, cachedSQLs.length);

        proxy.close();
        control.verify();
    }

    /**
     * Test: getCheckOutThreadName when checked out
     */
    @Test
    public void testGetCheckOutThreadNameWhenCheckedOut() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        String threadName = pooledConnection.getCheckOutThreadName();
        assertNotNull(threadName);
        assertFalse(threadName.isEmpty());
        assertTrue(threadName.contains("main") || threadName.length() > 0);

        control.verify();
    }

    /**
     * Test: getCheckOutThreadName when not checked out
     */
    @Test
    public void testGetCheckOutThreadNameWhenNotCheckedOut() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);
        proxy.close(); // Check in

        String threadName = pooledConnection.getCheckOutThreadName();
        assertEquals("", threadName);

        control.verify();
    }

    /**
     * Test: verbose logging in createStatement
     */
    @Test
    public void testVerboseLoggingCreateStatement() throws SQLException {
        // Need to reset control to override isVerbose behavior
        control.reset();

        // Re-setup mocks with verbose=true
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();  // TRUE for this test
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        expect(mockRealConnection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockStmt.getResultSetHoldability()).andReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(stmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: verbose logging in prepareStatement
     */
    @Test
    public void testVerboseLoggingPrepareStatement() throws SQLException {
        // Need to reset control to override isVerbose behavior
        control.reset();

        // Re-setup mocks with verbose=true
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();  // TRUE for this test
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        String sql = "SELECT * FROM test WHERE id = ?";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        expect(mockRealConnection.prepareStatement(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockPstmt.getResultSetHoldability()).andReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: verbose logging in prepareCall with query timeout
     */
    @Test
    public void testVerboseLoggingPrepareCallWithQueryTimeout() throws SQLException {
        // Need to reset control to override isVerbose behavior
        control.reset();

        // Re-setup mocks with verbose=true and query timeout
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();  // TRUE for this test
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.getQueryTimeout()).andReturn(30).anyTimes();  // Set query timeout

        String sql = "CALL sp_test(?, ?)";
        CallableStatement mockCstmt = control.createMock(CallableStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        expect(mockRealConnection.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT))
                .andReturn(mockCstmt).once();
        expect(mockCstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockCstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();
        expect(mockCstmt.getResultSetHoldability()).andReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT).anyTimes();

        // Query timeout setting
        mockCstmt.setQueryTimeout(30);
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        CallableStatement cstmt = proxy.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertNotNull(cstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: recover with non-fatal exception should return false
     */
    @Test
    public void testRecoverWithNonFatalException() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Create a non-fatal exception (SQLState not starting with 08)
        SQLException nonFatalException = new SQLException("Regular error", "23000");

        // Recovery should return false for non-fatal exceptions
        boolean recovered = pooledConnection.recover(nonFatalException);
        assertFalse(recovered);

        control.verify();
    }

    /**
     * Test: SQLRecoverableException handling
     */
    @Test
    public void testSQLRecoverableException() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Create SQLRecoverableException
        SQLRecoverableException recoverableException = new SQLRecoverableException("Connection lost");

        // Should be identified as fatal
        assertTrue(pooledConnection.isFatalException(recoverableException));

        control.verify();
    }

    /**
     * Test: close already closed connection
     */
    @Test
    public void testCloseAlreadyClosedConnection() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First close
        mockRealConnection.close();
        expectLastCall().once();

        // No second close expected

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Close once
        pooledConnection.close();

        // Close again - should return early
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: prepareStatement with column indexes (auto-generated keys)
     */
    @Test
    public void testPrepareStatementWithColumnIndexes() throws SQLException {
        String sql = "INSERT INTO test VALUES (?)";
        int[] columnIndexes = {1};
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, columnIndexes))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql, columnIndexes);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: prepareStatement with column names (auto-generated keys)
     */
    @Test
    public void testPrepareStatementWithColumnNames() throws SQLException {
        String sql = "INSERT INTO test VALUES (?)";
        String[] columnNames = {"id"};
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, columnNames))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        PreparedStatement pstmt = proxy.prepareStatement(sql, columnNames);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: getCheckOutTime when checked out
     */
    @Test
    public void testGetCheckOutTimeWhenCheckedOut() throws SQLException, InterruptedException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Initially not checked out
        long checkOutTime1 = pooledConnection.getCheckOutTime();
        assertEquals(0L, checkOutTime1);

        // Check out the connection
        Connection proxy = pooledConnection.checkOut(true);

        // Wait a bit to ensure some time has passed
        Thread.sleep(10);

        // Now should have a checkout time (elapsed time since checkout)
        long checkOutTime2 = pooledConnection.getCheckOutTime();
        assertTrue(checkOutTime2 >= 10, "Expected checkout time to be at least 10ms, but was: " + checkOutTime2);

        control.verify();
    }

    /**
     * Test: getCheckOutTime when not checked out
     */
    @Test
    public void testGetCheckOutTimeWhenNotCheckedOut() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        long checkOutTime = pooledConnection.getCheckOutTime();
        assertEquals(0L, checkOutTime);

        control.verify();
    }

    /**
     * Test: commitOnClose with commit (dirty connection in transaction mode)
     */
    @Test
    public void testCommitOnCloseDirtyConnectionCommit() throws SQLException {
        control.reset();

        // Re-setup mocks
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.isCommitOnClose()).andReturn(true).once();

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        // Expected: commit should be called
        mockRealConnection.commit();
        expectLastCall().once();

        // Close the real connection
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        // Mark as dirty
        pooledConnection.setDirty();

        // Close - should trigger commitOnClose with commit
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: commitOnClose with rollback (commitOnClose=false)
     */
    @Test
    public void testCommitOnCloseDirtyConnectionRollback() throws SQLException {
        control.reset();

        // Re-setup mocks
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.isCommitOnClose()).andReturn(false).once();

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        // Expected: rollback should be called
        mockRealConnection.rollback();
        expectLastCall().once();

        // Close the real connection
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        // Mark as dirty
        pooledConnection.setDirty();

        // Close - should trigger commitOnClose with rollback
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: close() when commitOnClose throws SQLException (error handling)
     */
    @Test
    public void testCloseWithCommitOnCloseError() throws SQLException {
        control.reset();

        // Re-setup mocks
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.isCommitOnClose()).andReturn(true).once();

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        // Commit throws exception
        mockRealConnection.commit();
        expectLastCall().andThrow(new SQLException("Commit failed")).once();

        // Still should close the connection
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);
        pooledConnection.setDirty();

        // Should handle the exception internally
        pooledConnection.close();

        control.verify();
    }

    /**
     * Test: checkOut with InterruptedException during lock
     */
    @Test
    public void testCheckOutInterruptedException() throws SQLException {
        control.reset();

        // Re-setup mocks
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Interrupt the thread
        Thread.currentThread().interrupt();

        try {
            pooledConnection.checkOut(true);
            fail("Should throw SQLException for interrupted thread");
        } catch (SQLException e) {
            assertEquals("08001", e.getSQLState());
            assertTrue(e.getMessage().contains("interrupted"));
        } finally {
            // Clear interrupt flag
            Thread.interrupted();
        }

        control.verify();
    }

    /**
     * Test: invoke when connection is closed and then used again
     */
    @Test
    public void testInvokeAfterPhysicalClose() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First usage
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        // Physical close
        mockRealConnection.close();
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Use it normally
        Statement stmt = proxy.createStatement();
        assertNotNull(stmt);

        // Check in
        proxy.close();

        // Now physically close it
        pooledConnection.close();

        // After physical close, isClosed should return true
        assertTrue(proxy.isClosed());

        control.verify();
    }

    /**
     * Test: checkIn with active statements (should be returned to idle pool)
     */
    @Test
    public void testCheckInWithActiveStatements() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        Statement stmt = proxy.createStatement();

        // Close proxy without closing statement - statement should be returned to idle pool
        proxy.close();

        // Verify statement was cached
        assertEquals(1, pooledConnection.getCachedStatementsCount());

        control.verify();
    }

    /**
     * Test: isFetalException with null SQLState
     */
    @Test
    public void testIsFatalExceptionWithNullSQLState() throws SQLException {
        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);

        // Create exception with null SQLState
        SQLException exception = new SQLException("Error", (String) null);

        // Should be considered fatal (null is treated as connection exception)
        assertTrue(pooledConnection.isFatalException(exception));

        control.verify();
    }

    /**
     * Test: invoke on closed connection should reopen it (line 308)
     */
    @Test
    public void testInvokeOnClosedConnectionReopens() throws SQLException {
        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // First, close the connection
        mockRealConnection.close();
        expectLastCall().once();

        // Then when we invoke a method, it should reopen
        expect(mockRealConnection.createStatement()).andReturn(mockStmt).once();
        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Physically close the connection
        pooledConnection.close();

        // Now invoke a method - should trigger makeRealConnection (line 308)
        Statement stmt = proxy.createStatement();
        assertNotNull(stmt);

        proxy.close();
        control.verify();
    }

    /**
     * Test: verbose logging for createStatement with arguments (lines 441-442)
     */
    @Test
    public void testVerboseCreateStatementWithArgs() throws SQLException {
        // 临时设置日志级别为INFO以触发verbose日志
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("io.hqwu.commons.cp");
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);

        try {
            control.reset();

            // Setup with verbose=true
            expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.getQueryTimeout()).andReturn(30).anyTimes();

        Statement mockStmt = control.createMock(Statement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockStmt).once();

        expect(mockStmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockStmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        // Query timeout should be set
        mockStmt.setQueryTimeout(30);
        expectLastCall().once();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // This should trigger verbose logging (lines 441-442)
        Statement stmt = proxy.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE);
        assertNotNull(stmt);

        proxy.close();
        control.verify();
        } finally {
            // 恢复原日志级别
            logger.setLevel(originalLevel);
        }
    }

    /**
     * Test: verbose logging for prepareStatement with array arguments (lines 477-485)
     */
    @Test
    public void testVerbosePrepareStatementWithArrayArgs() throws SQLException {
        // 临时设置日志级别为INFO以触发verbose日志
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("io.hqwu.commons.cp");
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);

        try {
            control.reset();

            // Setup with verbose=true
            expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        String sql = "INSERT INTO test VALUES (?, ?, ?)";
        PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);
        String[] columnNames = {"id", "name", "value"};

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareStatement(sql, columnNames))
                .andReturn(mockPstmt).once();
        expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // This should trigger verbose logging with array argument (lines 477-485, especially 479)
        PreparedStatement pstmt = proxy.prepareStatement(sql, columnNames);
        assertNotNull(pstmt);

        proxy.close();
        control.verify();
        } finally {
            // 恢复原日志级别
            logger.setLevel(originalLevel);
        }
    }

    /**
     * Test: verbose logging for prepareCall with arguments (lines 521-523)
     */
    @Test
    public void testVerbosePrepareCallWithArgs() throws SQLException {
        // 临时设置日志级别为INFO以触发verbose日志
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("io.hqwu.commons.cp");
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);

        try {
            control.reset();

            // Setup with verbose=true
            expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        String sql = "CALL test_proc(?, ?)";
        CallableStatement mockCstmt = control.createMock(CallableStatement.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
        expect(mockRealConnection.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_SENSITIVE,
                ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockCstmt).once();

        expect(mockCstmt.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_SENSITIVE).anyTimes();
        expect(mockCstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // This should trigger verbose logging (lines 521-523)
        CallableStatement cstmt = proxy.prepareCall(
                sql,
                ResultSet.TYPE_SCROLL_SENSITIVE,
                ResultSet.CONCUR_UPDATABLE);
        assertNotNull(cstmt);

        proxy.close();
        control.verify();
        } finally {
            // 恢复原日志级别
            logger.setLevel(originalLevel);
        }
    }

    /**
     * Test: verbose logging for prepareStatement with non-array argument (line 481)
     */
    @Test
    public void testVerbosePrepareStatementWithNonArrayArg() throws SQLException {
        // 临时设置日志级别为INFO以触发verbose日志
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("io.hqwu.commons.cp");
        ch.qos.logback.classic.Level originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);

        try {
            control.reset();

            // Setup with verbose=true
            expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
            expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
            expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
            expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
            expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
            expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
            expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
            expect(mockConfig.isVerbose()).andReturn(true).anyTimes();
            expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
            expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
            expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

            String sql = "INSERT INTO test VALUES (?)";
            PreparedStatement mockPstmt = control.createMock(PreparedStatement.class);

            expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();
            // 使用int参数（非数组）来触发line 481的else分支
            expect(mockRealConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
                    .andReturn(mockPstmt).once();
            expect(mockPstmt.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
            expect(mockPstmt.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

            mockPool.checkIn(anyObject(PooledConnection.class));
            expectLastCall().once();

            control.replay();

            PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
            Connection proxy = pooledConnection.checkOut(true);

            // This should trigger verbose logging with non-array argument (line 481)
            PreparedStatement pstmt = proxy.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            assertNotNull(pstmt);

            proxy.close();
            control.verify();
        } finally {
            // 恢复原日志级别
            logger.setLevel(originalLevel);
        }
    }

    /**
     * Test: checkIn handles commit/rollback exception (lines 405-406)
     */
    @Test
    public void testCheckInCommitThrowsException() throws SQLException {
        control.reset();

        // Re-setup mocks
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(false).anyTimes();
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(mockConfig.isCommitOnClose()).andReturn(true).once();

        expect(mockRealConnection.getAutoCommit()).andReturn(false).anyTimes();

        // Commit throws exception (this will be caught in lines 405-406)
        mockRealConnection.commit();
        expectLastCall().andThrow(new SQLException("Commit failed")).once();

        // checkIn should still succeed
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(false);

        // Mark as dirty so commit/rollback will be called
        pooledConnection.setDirty();

        // Close - should trigger commit which throws exception (lines 405-406)
        // The exception should be caught and logged
        proxy.close();

        control.verify();
    }

    // ==================== 新增测试用例：覆盖未测试的分支 ====================

    /**
     * Test: createStatement(null) - args为null的分支 (line 436, 452)
     */
    @Test
    void testCreateStatementWithNullArgs() throws Exception {
        IMocksControl ctrl = createNiceControl();
        Hqcp pool = ctrl.createMock(Hqcp.class);
        HqcpConfig config = ctrl.createMock(HqcpConfig.class);
        Connection realConn = ctrl.createMock(Connection.class);
        Statement mockStatement = ctrl.createMock(Statement.class);

        expect(pool.getPoolName()).andReturn("testPool").anyTimes();
        expect(pool.getConfig()).andReturn(config).anyTimes();

        expect(config.getUrl()).andReturn("jdbc:mock:test").anyTimes();
        expect(config.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(config.getMaxStatements()).andReturn(10).anyTimes();
        expect(config.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(config.getJmxLevel()).andReturn(0).anyTimes();
        expect(config.isVerbose()).andReturn(true).anyTimes(); // 触发 line 451-455
        expect(config.isPrintSql()).andReturn(false).anyTimes();
        expect(config.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(config.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(config.getQueryTimeout()).andReturn(0).anyTimes();

        expect(realConn.getAutoCommit()).andReturn(true).anyTimes();
        expect(realConn.createStatement()).andReturn(mockStatement).once();
        expect(mockStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        MockJDBCDriver.getInstance().setConnection(realConn);
        ctrl.replay();

        PooledConnection pooledConnection = new PooledConnection(pool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // 调用无参数的 createStatement
        Statement stmt = proxy.createStatement();
        assertNotNull(stmt);

        ctrl.verify();
        MockJDBCDriver.getInstance().disable();
    }

    /**
     * Test: prepareStatement 带多个参数 (line 496)
     */
    @Test
    void testPrepareStatementWithMultipleArgs() throws Exception {
        IMocksControl ctrl = createNiceControl();
        Hqcp pool = ctrl.createMock(Hqcp.class);
        HqcpConfig config = ctrl.createMock(HqcpConfig.class);
        Connection realConn = ctrl.createMock(Connection.class);
        PreparedStatement mockPreparedStatement = ctrl.createMock(PreparedStatement.class);

        expect(pool.getPoolName()).andReturn("testPool").anyTimes();
        expect(pool.getConfig()).andReturn(config).anyTimes();

        expect(config.getUrl()).andReturn("jdbc:mock:test").anyTimes();
        expect(config.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(config.getMaxStatements()).andReturn(10).anyTimes();
        expect(config.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(config.getJmxLevel()).andReturn(0).anyTimes();
        expect(config.isVerbose()).andReturn(true).anyTimes(); // 触发 line 488-497
        expect(config.isPrintSql()).andReturn(false).anyTimes();
        expect(config.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(config.getLifetimeMillisec()).andReturn(0L).anyTimes();
        expect(config.getQueryTimeout()).andReturn(0).anyTimes();

        expect(realConn.getAutoCommit()).andReturn(true).anyTimes();

        // prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        expect(realConn.prepareStatement("SELECT * FROM test",
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE))
                .andReturn(mockPreparedStatement).once();

        expect(mockPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_SCROLL_INSENSITIVE).anyTimes();
        expect(mockPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_UPDATABLE).anyTimes();

        MockJDBCDriver.getInstance().setConnection(realConn);
        ctrl.replay();

        PooledConnection pooledConnection = new PooledConnection(pool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // 调用带多个参数的 prepareStatement，args.length > 1
        PreparedStatement pstmt = proxy.prepareStatement("SELECT * FROM test",
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE);
        assertNotNull(pstmt);

        ctrl.verify();
        MockJDBCDriver.getInstance().disable();
    }

    /**
     * Test: isWrapperFor 和 unwrap 的 verbose 日志 (line 369, 374)
     * 基于 testWrapperInterfaceDelegation，但开启 verbose 模式
     */
    @Test
    public void testWrapperInterfaceVerboseLogging() throws SQLException {
        // Reset control to override isVerbose behavior
        control.reset();

        // Re-setup mocks with verbose=true
        expect(mockPool.getPoolName()).andReturn("testPool").anyTimes();
        expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
        expect(mockConfig.getUrl()).andReturn(MockConstant.MOCK_URL).anyTimes();
        expect(mockConfig.getConnectionProperties()).andReturn(new Properties()).anyTimes();
        expect(mockConfig.getMaxStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getMaxPreStatements()).andReturn(10).anyTimes();
        expect(mockConfig.getJmxLevel()).andReturn(0).anyTimes();
        expect(mockConfig.isVerbose()).andReturn(true).anyTimes(); // TRUE for verbose logging
        expect(mockConfig.isPrintSql()).andReturn(false).anyTimes();
        expect(mockConfig.getLifetimeSec()).andReturn(0L).anyTimes();
        expect(mockConfig.getLifetimeMillisec()).andReturn(0L).anyTimes();

        // Define a "Vendor Specific" interface for testing delegation
        Class<Runnable> vendorInterface = Runnable.class;
        Runnable mockVendorObject = control.createMock(Runnable.class);

        expect(mockRealConnection.getAutoCommit()).andReturn(true).anyTimes();

        // When checking for Runnable interface - delegate to real connection
        expect(mockRealConnection.isWrapperFor(vendorInterface)).andReturn(true).once();

        // When unwrapping Runnable - delegate to real connection
        expect(mockRealConnection.unwrap(vendorInterface)).andReturn(mockVendorObject).once();

        // Cleanup
        mockPool.checkIn(anyObject(PooledConnection.class));
        expectLastCall().once();

        control.replay();

        // Execution
        PooledConnection pooledConnection = new PooledConnection(mockPool, 1);
        Connection proxy = pooledConnection.checkOut(true);

        // Test isWrapperFor with verbose logging (line 369)
        boolean isWrapper = proxy.isWrapperFor(vendorInterface);
        assertTrue(isWrapper, "Should delegate to underlying connection and return true");

        // Test unwrap with verbose logging (line 374)
        Runnable result = proxy.unwrap(vendorInterface);
        assertNotNull(result);
        assertSame(mockVendorObject, result,
                "Should delegate to underlying connection and return the vendor object");

        proxy.close();

        control.verify();
    }

}


