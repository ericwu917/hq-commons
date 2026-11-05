package io.hqwu.commons.cp;

import io.hqwu.commons.cp.util.SqlMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.easymock.EasyMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PooledPreparedStatementTest {

    private PooledConnection mockPooledConnection;
    private PreparedStatement mockRealPreparedStatement;
    private ResultSet mockResultSet;

    @BeforeEach
    void setUp() {
        mockPooledConnection = createNiceMock(PooledConnection.class);
        mockRealPreparedStatement = createMock(PreparedStatement.class);
        mockResultSet = createMock(ResultSet.class);
    }

    /**
     * Test Case 1: ExecuteQuery with Parameters
     * Specification:
     * 1. setString (and other setters) should be delegated to the underlying statement.
     * 2. executeQuery should be delegated to the underlying statement.
     * 3. The internal logging state (sqlDoing) should correctly capture parameters.
     */
    @Test
    void testExecuteQueryWithParameters() throws SQLException {
        String sql = "SELECT * FROM users WHERE username = ? AND age > ?";
        // Arguments passed to constructor: [sql, resultSetType, resultSetConcurrency] or just [sql]
        // PooledStatement constructor logic implies we might need type/concurrency setup on the mock

        // 1. Setup Constructor Expectations
        // PooledConnection interaction
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();

        // PooledStatement calls these in constructor
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        // setString(1, "alice")
        mockRealPreparedStatement.setString(1, "alice");
        expectLastCall().once();

        // setInt(2, 25)
        mockRealPreparedStatement.setInt(2, 25);
        expectLastCall().once();

        // executeQuery()
        expect(mockRealPreparedStatement.execute()).andReturn(true).anyTimes(); // safeguard if execute is checked
        expect(mockRealPreparedStatement.executeQuery()).andReturn(mockResultSet).once();

        // Replay all mocks
        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        // Simulating the arguments passed by a Connection.prepareStatement(sql) call
        Object[] creatorArgs = new Object[]{sql};

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                101, // stmtId
                creatorArgs
        );

        // 4. Check Out Proxy and Invoke
        // The checkOut() method sets the internal state to 'open' and returns the proxy
        PreparedStatement proxyParams = (PreparedStatement) pooledPstmt.checkOut();

        // Invoke setters
        proxyParams.setString(1, "alice");
        proxyParams.setInt(2, 25);

        // Invoke execute
        ResultSet rs = proxyParams.executeQuery();

        // 5. Verification
        assertNotNull(rs, "ResultSet should not be null");

        // Verify internal logging state (Protected method access allowed in same package)
        String debugSql = pooledPstmt.getSqlDoing();
        // The implementation replaces ? with values.
        // Expect: "SELECT * FROM users WHERE username = 'alice' AND age > 25"
        // Note: The implementation detail might vary on spacing or quoting, adhering to generic SQL text rules.
        assertTrue(debugSql.contains("'alice'"), "SQL log should contain 'alice'");
        assertTrue(debugSql.contains("25"), "SQL log should contain 25");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 2: ExecuteUpdate
     * Requirement: executeUpdate() should be delegated to the underlying statement
     * and return the correct row count.
     */
    @Test
    void testExecuteUpdate() throws SQLException {
        String sql = "UPDATE users SET age = ? WHERE username = ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setInt(1, 30);
        expectLastCall().once();

        mockRealPreparedStatement.setString(2, "bob");
        expectLastCall().once();

        expect(mockRealPreparedStatement.executeUpdate()).andReturn(5).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                102,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setInt(1, 30);
        proxy.setString(2, "bob");
        int rows = proxy.executeUpdate();

        // 5. Verification
        assertEquals(5, rows, "Update count should be 5");

        String debugSql = pooledPstmt.getSqlDoing();
        assertTrue(debugSql.contains("age = 30"), "SQL log should contain 'age = 30'");
        assertTrue(debugSql.contains("'bob'"), "SQL log should contain 'bob'");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 3: Execute
     * Requirement: execute() should be delegated appropriately.
     */
    @Test
    void testExecute() throws SQLException {
        String sql = "DELETE FROM users WHERE age < ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setInt(1, 18);
        expectLastCall().once();

        expect(mockRealPreparedStatement.execute()).andReturn(false).once(); // false means no ResultSet
        expect(mockRealPreparedStatement.getUpdateCount()).andReturn(1).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                103,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setInt(1, 18);
        boolean hasResult = proxy.execute();

        // 5. Verification
        assertFalse(hasResult, "execute should return false for DELETE");

        String debugSql = pooledPstmt.getSqlDoing();
        assertTrue(debugSql.contains("age < 18"), "SQL log should contain 'age < 18'");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 4: SetNull Handling
     * Requirement: setNull should be delegated and logged as NULL in SQL string.
     */
    @Test
    void testSetNull() throws SQLException {
        String sql = "INSERT INTO users (username, nickname) VALUES (?, ?)";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setString(1, "charlie");
        expectLastCall().once();

        mockRealPreparedStatement.setNull(2, java.sql.Types.VARCHAR);
        expectLastCall().once();

        expect(mockRealPreparedStatement.executeUpdate()).andReturn(1).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                104,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setString(1, "charlie");
        proxy.setNull(2, java.sql.Types.VARCHAR);
        proxy.executeUpdate();

        // 5. Verification
        String debugSql = pooledPstmt.getSqlDoing();
        assertTrue(debugSql.contains("'charlie'"), "SQL log should contain 'charlie'");
        assertTrue(debugSql.contains("NULL"), "SQL log should contain NULL for nickname");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 5: Batch Operations
     * Requirement: addBatch() should be delegated.
     */
    @Test
    void testAddBatch() throws SQLException {
        String sql = "INSERT INTO users (username) VALUES (?)";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setString(1, "user1");
        expectLastCall().once();

        mockRealPreparedStatement.addBatch();
        expectLastCall().once();

        mockRealPreparedStatement.setString(1, "user2");
        expectLastCall().once();

        mockRealPreparedStatement.addBatch();
        expectLastCall().once();

        expect(mockRealPreparedStatement.executeBatch()).andReturn(new int[]{1, 1}).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                105,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setString(1, "user1");
        proxy.addBatch();

        proxy.setString(1, "user2");
        proxy.addBatch();

        int[] result = proxy.executeBatch();

        // 5. Verification
        assertEquals(2, result.length);

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 6: Clear Parameters
     * Requirement: clearParameters() should be delegated.
     */
    @Test
    void testClearParameters() throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setInt(1, 100);
        expectLastCall().once();

        mockRealPreparedStatement.clearParameters();
        expectLastCall().once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                106,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setInt(1, 100);
        proxy.clearParameters();

        // 5. Verification
        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 7: MetaData
     * Requirement: getMetaData() and getParameterMetaData() should be delegated.
     */
    @Test
    void testMetaData() throws SQLException {
        String sql = "SELECT * FROM users";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        java.sql.ResultSetMetaData mockMetaData = createMock(java.sql.ResultSetMetaData.class);
        expect(mockRealPreparedStatement.getMetaData()).andReturn(mockMetaData).once();

        java.sql.ParameterMetaData mockParamMetaData = createMock(java.sql.ParameterMetaData.class);
        expect(mockRealPreparedStatement.getParameterMetaData()).andReturn(mockParamMetaData).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet, mockMetaData, mockParamMetaData);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                107,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        assertNotNull(proxy.getMetaData());
        assertNotNull(proxy.getParameterMetaData());

        // 5. Verification
        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet, mockMetaData, mockParamMetaData);
    }

    /**
     * Test Case 8: SetObject
     * Requirement: setObject() should be delegated and logged correctly.
     */
    @Test
    void testSetObject() throws SQLException {
        String sql = "UPDATE product SET details = ? WHERE id = ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        Object complexObj = new Object() {
            public String toString() {
                return "ComplexData";
            }
        };

        mockRealPreparedStatement.setObject(1, complexObj);
        expectLastCall().once();

        mockRealPreparedStatement.setInt(2, 50);
        expectLastCall().once();

        expect(mockRealPreparedStatement.executeUpdate()).andReturn(1).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                108,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setObject(1, complexObj);
        proxy.setInt(2, 50);
        proxy.executeUpdate();

        // 5. Verification
        String debugSql = pooledPstmt.getSqlDoing();
        assertTrue(debugSql.contains("'ComplexData'"), "SQL log should contain toString() of object");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * 内联参数含 ' 和 \ 时先转义，内联后的 SQL 能被 SqlMasker 解析，敏感值被掩
     */
    @Test
    void testInlineParamsEscapedAndMaskable() throws SQLException {
        String sql = "UPDATE users SET name = ?, password = ?, flag = ? WHERE id = ?";

        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        mockRealPreparedStatement.setString(1, "O'Brien-Smith");
        expectLastCall().once();
        mockRealPreparedStatement.setString(2, "C:\\pw\\it's-a-secret");
        expectLastCall().once();
        mockRealPreparedStatement.setObject(3, '\'');
        expectLastCall().once();
        mockRealPreparedStatement.setInt(4, 7);
        expectLastCall().once();
        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection, mockRealPreparedStatement, 110, new Object[]{sql});
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();
        proxy.setString(1, "O'Brien-Smith");
        proxy.setString(2, "C:\\pw\\it's-a-secret");
        proxy.setObject(3, '\'');
        proxy.setInt(4, 7);

        String debugSql = pooledPstmt.getSqlDoing();
        assertEquals("UPDATE users SET name = 'O''Brien-Smith', password = 'C:\\\\pw\\\\it''s-a-secret',"
                + " flag = '''' WHERE id = 7", debugSql.trim());

        SqlMasker masker = new SqlMasker("****", java.util.Set.of("name", "password"));
        String masked = masker.maskSensitiveFields(debugSql);
        assertFalse(masked.contains("Brien-Smith"), masked);
        assertFalse(masked.contains("secret"), masked);
        assertTrue(masked.startsWith("UPDATE users SET name = '"), masked);
        assertTrue(masked.contains(", flag = '''' WHERE id = 7"), masked);

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * 预编译 SQL 字面值里的 ? 不当占位符：绑定值内联到真正的占位符
     */
    @Test
    void testSqlDoingSkipsQuestionMarkInLiteral() throws SQLException {
        String sql = "select * from t where note = 'a?' and password = ?";

        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();
        mockRealPreparedStatement.setString(1, "123456");
        expectLastCall().once();
        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection, mockRealPreparedStatement, 111, new Object[]{sql});
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();
        proxy.setString(1, "123456");

        assertEquals("select * from t where note = 'a?' and password = '123456'", pooledPstmt.getSqlDoing().trim());

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    @Test
    void testIndexOfPlaceholder() {
        // 普通占位符、from 起点
        assertEquals(4, PooledPreparedStatement.indexOfPlaceholder("a = ? and ?", 0));
        assertEquals(10, PooledPreparedStatement.indexOfPlaceholder("a = ? and ?", 5));
        assertEquals(-1, PooledPreparedStatement.indexOfPlaceholder("a = 1", 0));
        // 单引号：含 '' 与 \ 转义
        assertEquals(12, PooledPreparedStatement.indexOfPlaceholder("x = 'a?''?' ?", 0));
        assertEquals(12, PooledPreparedStatement.indexOfPlaceholder("x = 'a\\'?b' ?", 0));
        // 双引号：含 "" 与 \ 转义
        assertEquals(12, PooledPreparedStatement.indexOfPlaceholder("x = \"a?\"\"?\" ?", 0));
        assertEquals(12, PooledPreparedStatement.indexOfPlaceholder("x = \"a\\\"?b\" ?", 0));
        // 反引号：含 `` 转义，\ 不转义
        assertEquals(10, PooledPreparedStatement.indexOfPlaceholder("`a?``?` = ?", 0));
        assertEquals(7, PooledPreparedStatement.indexOfPlaceholder("`a\\` = ?", 0));
        // 块注释、行注释
        assertEquals(14, PooledPreparedStatement.indexOfPlaceholder("x = /* ? */ 1 ?", 0));
        assertEquals(14, PooledPreparedStatement.indexOfPlaceholder("x -- a ?\n y = ?", 0));
        // -- 后不跟空格不是注释；单个 / 不是注释
        assertEquals(4, PooledPreparedStatement.indexOfPlaceholder("x --? ", 0));
        assertEquals(6, PooledPreparedStatement.indexOfPlaceholder("x / 2 ?", 0));
        // 未闭合的引号 / 注释延伸到结尾
        assertEquals(-1, PooledPreparedStatement.indexOfPlaceholder("x = 'a?", 0));
        assertEquals(-1, PooledPreparedStatement.indexOfPlaceholder("x = 'a\\", 0));
        assertEquals(-1, PooledPreparedStatement.indexOfPlaceholder("x = /* ?", 0));
        assertEquals(-1, PooledPreparedStatement.indexOfPlaceholder("x -- ?", 0));
    }

    /**
     * Test Case 9: Partial Parameters Logging
     * Requirement: getSqlDoing() should handle cases where not all parameters are set.
     */
    @Test
    void testSqlDoingWithPartialParams() throws SQLException {
        String sql = "SELECT * FROM users WHERE col1 = ? AND col2 = ? AND col3 = ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        mockRealPreparedStatement.setInt(1, 10);
        expectLastCall().once();

        // We do NOT set param 2

        mockRealPreparedStatement.setInt(3, 30);
        expectLastCall().once();

        // We won't execute because it might fail in real DB, but we just want to check logging state before execution
        // or simulating a failure scenario where we want to see what was set.

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                109,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setInt(1, 10);
        proxy.setInt(3, 30);

        // 5. Verification
        String debugSql = pooledPstmt.getSqlDoing();
        // Param 2 is missing. The implementation usually iterates over params array.
        // If param 2 is null (default in array), it might print as NULL or keep ? depending on implementation details.
        // Looking at code:
        // Object p = paras[i]; ... if (p == null) sb.append("NULL");

        assertTrue(debugSql.contains("col1 = 10"), "Should contain set param");
        // Logic check: The implementation prints "NULL" for nulls in the array.
        // Since we didn't set param 2, it is null in the array.
        assertTrue(debugSql.contains("col2 = NULL"), "Unset param should appear as NULL in simplified logger");
        assertTrue(debugSql.contains("col3 = 30"), "Should contain last param");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case 10: Rich Type Setters (Date, Timestamp, Blob)
     * Requirement: Ensure non-primitive/string types are delegated correctly
     * and do not crash the internal logging mechanism.
     */
    @Test
    void testRichTypeSetters() throws SQLException {
        String sql = "INSERT INTO events (evt_date, evt_time, data_blob) VALUES (?, ?, ?)";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        java.sql.Date curDate = new java.sql.Date(System.currentTimeMillis());
        java.sql.Timestamp curTs = new java.sql.Timestamp(System.currentTimeMillis());
        java.sql.Blob mockBlob = createNiceMock(java.sql.Blob.class); // NiceMock for toString safety if needed

        mockRealPreparedStatement.setDate(1, curDate);
        expectLastCall().once();

        mockRealPreparedStatement.setTimestamp(2, curTs);
        expectLastCall().once();

        mockRealPreparedStatement.setBlob(3, mockBlob);
        expectLastCall().once();

        expect(mockRealPreparedStatement.executeUpdate()).andReturn(1).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet, mockBlob);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                110,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        proxy.setDate(1, curDate);
        proxy.setTimestamp(2, curTs);
        proxy.setBlob(3, mockBlob);
        proxy.executeUpdate();

        // 5. Verification
        String debugSql = pooledPstmt.getSqlDoing();
        // Just verify it doesn't crash and contains string representations.
        // The current implementation calls toString() on objects.
        assertTrue(debugSql.contains(curDate.toString()), "SQL log should contain date");
        // Check robustness for Blob (likely prints object hash or mock string)
        assertNotNull(debugSql);

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet, mockBlob);
    }

    /**
     * Test Case 11: Parameter Index Safety
     * Requirement: If index is out of bounds (e.g. > parameter count),
     * it should typically be passed to the underlying driver (logic error in user code),
     * but the proxy's logging logic MUST NOT throw ArrayIndexOutOfBoundsException.
     */
    @Test
    void testParameterIndexSafety() throws SQLException {
        // SQL has 1 parameter
        String sql = "SELECT * FROM users WHERE id = ?";

        // 1. Setup Constructor Expectations
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // 2. Setup Test Expectations
        // User mistakenly sets index 99. The real driver works or throws SQLException.
        // We expect the call to go through to the mock.
        mockRealPreparedStatement.setString(99, "invalid-index");
        expectLastCall().once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        // 3. Initialize Subject
        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                111,
                new Object[]{sql}
        );

        // 4. Check Out Proxy and Invoke
        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        // This should NOT throw IndexOutOfBoundsException from PooledPreparedStatement internal array
        assertDoesNotThrow(() -> proxy.setString(99, "invalid-index"));

        // 5. Verification
        // The logging state should remain valid (likely just didn't log this parameter because it's out of range)
        String debugSql = pooledPstmt.getSqlDoing();
        assertNotNull(debugSql);

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case: Wrapper Support
     * Requirement: Verify isWrapperFor and unwrap work correctly for PreparedStatement proxy.
     * The implementation should:
     * 1. Check if the requested interface is implemented by the InvocationHandler (PooledPreparedStatement)
     * 2. Check if the requested interface is implemented by the Proxy itself
     * 3. Delegate to the underlying statement's isWrapperFor/unwrap
     */
    @Test
    void testWrapperSupport() throws SQLException {
        String sql = "SELECT * FROM test";

        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                201,
                new Object[]{sql}
        );

        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        // Test 1: isWrapperFor with PreparedStatement interface (proxy implements it)
        assertTrue(proxy.isWrapperFor(PreparedStatement.class),
            "Proxy should be a wrapper for PreparedStatement interface");

        // Test 2: unwrap with PreparedStatement interface (should return proxy itself)
        PreparedStatement unwrappedStmt = proxy.unwrap(PreparedStatement.class);
        assertSame(proxy, unwrappedStmt,
            "Unwrapping PreparedStatement should return the proxy itself");

        // Test 3: isWrapperFor with PooledPreparedStatement class (handler type)
        assertTrue(proxy.isWrapperFor(PooledPreparedStatement.class),
            "Proxy should be a wrapper for PooledPreparedStatement (the handler)");

        // Test 4: unwrap with PooledPreparedStatement class (should return the handler)
        PooledPreparedStatement unwrappedHandler = proxy.unwrap(PooledPreparedStatement.class);
        assertSame(pooledPstmt, unwrappedHandler,
            "Unwrapping PooledPreparedStatement should return the handler itself");

        // Test 5: isWrapperFor with PooledStatement class (parent handler type)
        assertTrue(proxy.isWrapperFor(PooledStatement.class),
            "Proxy should be a wrapper for PooledStatement (parent handler)");

        // Test 6: unwrap with PooledStatement class (should return the handler)
        PooledStatement unwrappedParent = proxy.unwrap(PooledStatement.class);
        assertSame(pooledPstmt, unwrappedParent,
            "Unwrapping PooledStatement should return the handler itself");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case: Wrapper Support - Delegation
     * Verify that when the requested interface is not implemented by handler or proxy,
     * it delegates to the underlying real statement.
     */
    @Test
    void testWrapperSupportDelegation() throws SQLException {
        String sql = "SELECT * FROM test";

        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // Define a vendor-specific interface
        Class<Runnable> vendorInterface = Runnable.class;
        Runnable mockVendorObject = createMock(Runnable.class);

        // Delegation expectations
        expect(mockRealPreparedStatement.isWrapperFor(vendorInterface)).andReturn(true).once();
        expect(mockRealPreparedStatement.unwrap(vendorInterface)).andReturn(mockVendorObject).once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet, mockVendorObject);

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                202,
                new Object[]{sql}
        );

        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        // Test isWrapperFor delegation
        assertTrue(proxy.isWrapperFor(vendorInterface),
            "Should delegate to underlying statement and return true");

        // Test unwrap delegation
        Runnable unwrapped = proxy.unwrap(vendorInterface);
        assertSame(mockVendorObject, unwrapped,
            "Should delegate to underlying statement and return the vendor object");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }

    /**
     * Test Case: Wrapper Support - Null Handling
     */
    @Test
    void testWrapperSupportNullHandling() throws SQLException {
        String sql = "SELECT * FROM test";

        // Setup
        expect(mockPooledConnection.getConnectionName()).andReturn("test-conn").anyTimes();
        expect(mockRealPreparedStatement.getResultSetType()).andReturn(ResultSet.TYPE_FORWARD_ONLY).anyTimes();
        expect(mockRealPreparedStatement.getResultSetConcurrency()).andReturn(ResultSet.CONCUR_READ_ONLY).anyTimes();

        // When unwrap(null) throws NullPointerException, it will trigger close() and setFatalExceptionHappened
        mockRealPreparedStatement.close();
        expectLastCall().once();
        mockPooledConnection.setFatalExceptionHappened(true);
        expectLastCall().once();

        replay(mockPooledConnection, mockRealPreparedStatement, mockResultSet);

        PooledPreparedStatement pooledPstmt = new PooledPreparedStatement(
                mockPooledConnection,
                mockRealPreparedStatement,
                203,
                new Object[]{sql}
        );

        PreparedStatement proxy = (PreparedStatement) pooledPstmt.checkOut();

        // Test 1: isWrapperFor with null should return false
        assertFalse(proxy.isWrapperFor(null),
            "isWrapperFor(null) should return false");

        // Test 2: unwrap with null should throw exception
        // Note: NullPointerException will trigger close() because it's not a SQLException
        assertThrows(NullPointerException.class, () -> proxy.unwrap(null),
            "unwrap(null) should throw NullPointerException");

        verify(mockPooledConnection, mockRealPreparedStatement, mockResultSet);
    }
}


