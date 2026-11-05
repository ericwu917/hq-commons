package io.hqwu.commons.cp.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Created with IntelliJ IDEA for hq-commons-parent
 *
 * @author taige (Wu, Hongqiang)
 * Date: 2026-01-13
 * Time: 17:16
 *
 */
class SqlMaskerTest {

    private SqlMasker sqlMasker;
    private final String maskPattern = "****";
    private final Set<String> sensitiveFields = new HashSet<>(Arrays.asList("password", "email", "secret", "credit_card"));

    @BeforeEach
    void setUp() {
        sqlMasker = new SqlMasker(maskPattern, sensitiveFields);
    }

    @Nested
    class InsertStatements {
        /**
         * Covers: 1.1 单行INSERT语句
         */
        @Test
        void testSingleRowInsert() {
            String sql = "INSERT INTO users (id, name, password, email) VALUES (1, 'John', 'secret123', 'john@example.com')";
            String expected = "INSERT INTO users (id, name, password, email) VALUES (1, 'John', 'sec****23', 'john********.com')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2.1 多行INSERT语句
         */
        @Test
        void testMultiRowInsert() {
            String sql = "INSERT INTO users (id, name, password, email) VALUES (1, 'John', 'secret123', 'john@example.com'), (2, 'Mary', 'secret456', 'mary@example.com')";
            String expected = "INSERT INTO users (id, name, password, email) VALUES (1, 'John', 'sec****23', 'john********.com'), (2, 'Mary', 'sec****56', 'mary********.com')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 带括号的VALUES
         * Covers: 1.2 INSERT (column1) VALUES ('2')
         */
        @Test
        void testInsertWithSimpleParenthesizedValue() {
            // jsqlparser treats VALUES ('value') as a Parenthesis-wrapped expression.
            String sql = "INSERT INTO users (password) VALUES ('secret123')";
            String expected = "INSERT INTO users (password) VALUES ('sec****23')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 带多层括号的VALUES
         * Covers: 1.2 INSERT (column1) VALUES (('2'))
         */
        @Test
        void testInsertWithExplicitParenthesizedValue() {
            String sql = "INSERT INTO users (password) VALUES (('secret123'))";
            String expected = "INSERT INTO users (password) VALUES (('sec****23'))";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2.2 INSERT (column1) VALUES ('2'), ('5')
         */
        @Test
        void testSingleColumnMultiRowInsert() {
            String sql = "INSERT INTO password_history (password) VALUES ('secret1'), ('secret2')";
            String expected = "INSERT INTO password_history (password) VALUES ('se****1'), ('se****2')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3. INSERT ... SELECT 语句
         */
        @Test
        void testInsertSelect() {
            String sql = "INSERT INTO backup_users (id, name, password, email) SELECT id, name, password, email FROM users WHERE password = '' OR password='simple_password'";
            String expected = "INSERT INTO backup_users (id, name, password, email) SELECT id, name, password, email FROM users WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 4. INSERT ... ON DUPLICATE KEY UPDATE
         */
        @Test
        void testInsertOnDuplicateKeyUpdate() {
            String sql = "INSERT INTO table_name (id, password) VALUES (123, 'secret') ON DUPLICATE KEY UPDATE password = 'new_secret'";
            String expected = "INSERT INTO table_name (id, password) VALUES (123, 's****t') ON DUPLICATE KEY UPDATE password = 'new****ret'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testInsertOnDuplicateKeyUpdateWithCase() {
            String sql = "INSERT INTO users (id, password) VALUES (1, 'secret') ON DUPLICATE KEY UPDATE password = CASE WHEN id=1 THEN 'new_secret' ELSE 'old_secret' END";
            String expected = "INSERT INTO users (id, password) VALUES (1, 's****t') ON DUPLICATE KEY UPDATE password = CASE WHEN id = 1 THEN 'new****ret' ELSE 'old****ret' END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }
    }

    @Nested
    class UpdateStatements {
        /**
         * Covers: 1.1 基础UPDATE语句 UPDATE ... SET (a, b, c) = (VALUES '1', '2', '3')
         */
        @Test
        void testUpdateWithTupleAssignment() {
            String sql =      "UPDATE users SET (name, password, email) = (VALUES ('John', 'new_secret', 'new@email.com')) WHERE id = 1";
            String expected = "UPDATE users SET (name, password, email) = (VALUES ('John', 'new****ret', 'new@*****.com')) WHERE id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1.2 基础UPDATE语句 UPDATE ... SET column2 = (SELECT ... )
         */
        @Test
        void testUpdateSetWithSubquery() {
            String sql = "UPDATE users SET email = (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test@example.com')";
            String expected = "UPDATE users SET email = (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test********.com')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1.2 基础UPDATE语句 UPDATE ... SET column2 = (SELECT ... )
         */
        @Test
        void testUpdateSetWithSubqueryAndOtherFields() {
            String sql = "UPDATE users SET name = 'John', password = 'secret123', email = (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test@example.com')";
            String expected = "UPDATE users SET name = 'John', password = 'sec****23', email = (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test********.com')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1.2 基础UPDATE语句 UPDATE ... SET column2 = (SELECT ... )
         */
        @Test
        void testUpdateSetWithSubqueryInTuple() {
            String sql = "UPDATE users SET (name, password, email) = (VALUES ('John', 'new_secret', (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test@example.com'))) WHERE id = 1";
            String expected = "UPDATE users SET (name, password, email) = (VALUES ('John', 'new****ret', (SELECT email FROM user_emails WHERE user_id = 1 AND email = 'test********.com'))) WHERE id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1.3 基础UPDATE语句 UPDATE ... SET column1 = value1 ...
         */
        @Test
        void testBasicUpdate_SetClause() {
            String sql = "UPDATE users SET enabled = true, password = 'new_password' WHERE id = 1";
            String expected = "UPDATE users SET enabled = true, password = 'new_****word' WHERE id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1.3 基础UPDATE语句 UPDATE ... SET column1 = value1 ...
         */
        @Test
        void testBasicUpdate_WhereClause() {
            String sql = "UPDATE users SET enabled = false WHERE password = '' OR password='simple_password'";
            String expected = "UPDATE users SET enabled = false WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2. 带JOIN的UPDATE
         */
        @Test
        void testUpdateWithJoin() {
            String sql =      "UPDATE users u JOIN temp_users t ON u.id = t.id SET u.password = t.password, t.sync = true WHERE u.password = 'simple_password'";
            String expected = "UPDATE users u JOIN temp_users t ON u.id = t.id SET u.password = t.password, t.sync = true WHERE u.password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2. 带JOIN的UPDATE
         */
        @Test
        void testUpdateWithJoinOnCondition() {
            String sql =      "UPDATE users u JOIN user_secrets s ON u.email = s.email SET u.password = 'verified' WHERE s.email = 'test@example.com'";
            String expected = "UPDATE users u JOIN user_secrets s ON u.email = s.email SET u.password = 've****ed' WHERE s.email = 'test********.com'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.1.1 UPDATE ... SET column1 = CASE switchColumn WHEN (SELECT ...)
         */
        @Test
        void testUpdate_CaseWhenWithSubquery() {
            String sql =      "UPDATE users SET credit_card = CASE secret WHEN (SELECT secret_key FROM secrets WHERE password = 'pass1234') THEN 'card123' END";
            String expected = "UPDATE users SET credit_card = CASE secret WHEN (SELECT secret_key FROM secrets WHERE password = 'pa****34') THEN 'ca****3' END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.1.2 UPDATE ... SET column1 = CASE switchColumn WHEN 'secret'
         */
        @Test
        void testUpdateWithCaseSwitch() {
            String sql =      "UPDATE users SET credit_card = CASE secret WHEN 'key1234' THEN 'card1234' ELSE 'card5678' END";
            String expected = "UPDATE users SET credit_card = CASE secret WHEN 'ke****4' THEN 'ca****34' ELSE 'ca****78' END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.2 UPDATE ... SET column1 = CASE WHEN password='secret'
         */
        @Test
        void testUpdateWithCaseWhenCondition() {
            String sql = "UPDATE users SET status = CASE WHEN password = 'secret' THEN 'active' ELSE 'inactive' END";
            String expected = "UPDATE users SET status = CASE WHEN password = 's****t' THEN 'active' ELSE 'inactive' END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.3 UPDATE ... SET column1 = CASE ... THEN (SELECT ...)
         */
        @Test
        void testUpdate_CaseThenWithSubquery() {
            String sql = "UPDATE users SET credit_card = CASE WHEN status = 'active' THEN (SELECT card FROM cards WHERE password = 'pass1234') END";
            String expected = "UPDATE users SET credit_card = CASE WHEN status = 'active' THEN (SELECT card FROM cards WHERE password = 'pa****34') END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.5 UPDATE ... SET column1 = CASE ... ELSE (SELECT ...)
         */
        @Test
        void testUpdate_CaseElseWithSubquery() {
            String sql = "UPDATE users SET credit_card = CASE WHEN status = 'inactive' THEN 'default' ELSE (SELECT card FROM cards WHERE password = 'pass1234') END";
            String expected = "UPDATE users SET credit_card = CASE WHEN status = 'inactive' THEN 'de****t' ELSE (SELECT card FROM cards WHERE password = 'pa****34') END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.4 UPDATE ... SET column1 = CASE ... THEN 'secret'
         * Covers: 3.6 UPDATE ... SET column1 = CASE ... ELSE 'secret'
         */
        @Test
        void testUpdateWithCase() {
            String sql = "UPDATE users SET password = CASE WHEN id = 1 THEN 'secret1' ELSE 'secret2' END";
            String expected = "UPDATE users SET password = CASE WHEN id = 1 THEN 'se****1' ELSE 'se****2' END";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

    }

    @Nested
    class SelectStatements {
        /**
         * Covers: 0. SELECT * FROM ... WHERE ...
         */
        @Test
        void testBasicSelect() {
            String sql = "SELECT column1, column2 FROM table_name WHERE password = '' OR password='simple_password'";
            String expected = "SELECT column1, column2 FROM table_name WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 1. SELECT column1, (SELECT ... ) FROM ...
         */
        @Test
        void testSelectWithSubqueryInSelectList() {
            String sql = "SELECT name, (SELECT password FROM user_secrets s WHERE s.user_id = u.id AND s.password = 'sub_secret') FROM users u";
            String expected = "SELECT name, (SELECT password FROM user_secrets s WHERE s.user_id = u.id AND s.password = 'sub****ret') FROM users u";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2. SELECT * FROM (SELECT ... )
         */
        @Test
        void testSelectWithSubqueryInFromClause() {
            String sql =      "SELECT * FROM (SELECT id, password FROM users WHERE password = 'secret') AS u WHERE u.id = 1";
            String expected = "SELECT * FROM (SELECT id, password FROM users WHERE password = 's****t') AS u WHERE u.id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.1 SELECT * FROM ... JOIN (SELECT ... )
         */
        @Test
        void testSelectWithSubqueryInJoinClause() {
            String sql = "SELECT * FROM users u JOIN (SELECT user_id, email FROM user_emails WHERE email = 'test@example.com') AS e ON u.id = e.user_id";
            String expected = "SELECT * FROM users u JOIN (SELECT user_id, email FROM user_emails WHERE email = 'test********.com') AS e ON u.id = e.user_id";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.2 SELECT * FROM ... JOIN ... ON ...
         */
        @Test
        void testSelectWithJoinOnCondition_NoMasking() {
            String sql =      "SELECT * FROM users u JOIN user_secrets s ON u.email = s.email WHERE u.id = 1";
            String expected = "SELECT * FROM users u JOIN user_secrets s ON u.email = s.email WHERE u.id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 3.2 SELECT * FROM ... JOIN ... ON ...
         */
        @Test
        void testSelectWithJoinOnCondition_WithMasking() {
            String sql = "SELECT * FROM users u JOIN user_secrets s ON u.email = 'test@example.com' WHERE u.id = 1";
            String expected = "SELECT * FROM users u JOIN user_secrets s ON u.email = 'test********.com' WHERE u.id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testSelectWithJoin() {
            String sql =      "SELECT t1.column1, t2.column2 FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id WHERE password = '' OR password='simple_password'";
            String expected = "SELECT t1.column1, t2.column2 FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * 4. 被括号包裹的 SELECT 查询，例如作为子查询或在 UNION 操作中
         */
        @Test
        void testSelectWithUnion() {
            String sql = "SELECT * FROM users WHERE password = '123' UNION SELECT * FROM admins WHERE password = '456'";
            String expected = "SELECT * FROM users WHERE password = '****' UNION SELECT * FROM admins WHERE password = '****'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * 4. 被括号包裹的 SELECT 查询，例如作为子查询或在 UNION ALL 操作中
         */
        @Test
        void testSelectWithUnionAll() {
            String sql = "SELECT * FROM users WHERE password = '123' UNION ALL SELECT * FROM admins WHERE password = '456'";
            String expected = "SELECT * FROM users WHERE password = '****' UNION ALL SELECT * FROM admins WHERE password = '****'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * 5. 多个 SELECT 查询通过集合操作（如 INTERSECT）连接而成的组合查询
         */
        @Test
        void testSelectWithIntersect() {
            String sql = "SELECT password FROM users WHERE password = '123' INTERSECT SELECT password FROM admins WHERE password = '456'";
            String expected = "SELECT password FROM users WHERE password = '****' INTERSECT SELECT password FROM admins WHERE password = '****'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * 5. 多个 SELECT 查询通过集合操作（如 EXCEPT）连接而成的组合查询
         */
        @Test
        void testSelectWithExcept() {
            String sql = "SELECT password FROM users WHERE password = '123' EXCEPT SELECT password FROM admins WHERE password = '456'";
            String expected = "SELECT password FROM users WHERE password = '****' EXCEPT SELECT password FROM admins WHERE password = '****'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 6. SQL 中 LATERAL 关键字引入的子查询
         */
        @Test
        void testSelectWithLateralSubSelect() {
            String sql = "SELECT * FROM users u, LATERAL (SELECT * FROM user_secrets s WHERE s.user_id = u.id AND s.password = 'secret') AS s";
            String expected = "SELECT * FROM users u, LATERAL(SELECT * FROM user_secrets s WHERE s.user_id = u.id AND s.password = 's****t') AS s";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

    }

    @Nested
    class DeleteStatements {
        /**
         * Covers: 1. 基础DELETE语句
         */
        @Test
        void testBasicDelete() {
            String sql = "DELETE FROM users WHERE password = '' OR password='simple_password'";
            String expected = "DELETE FROM users WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2. DELETE ... FROM ... JOIN
         */
        @Test
        void testDeleteWithJoin() {
            String sql = "DELETE t1, t2 FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id WHERE password = '' OR password='simple_password'";
            String expected = "DELETE t1, t2 FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id WHERE password = '****' OR password = 'simp*******word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        /**
         * Covers: 2. DELETE ... FROM ... JOIN
         */
        @Test
        void testDeleteWithJoinOnCondition() {
            String      sql = "DELETE u FROM users u JOIN user_secrets s ON u.email = 'test@example.com' WHERE u.id = 1";
            String expected = "DELETE u FROM users u JOIN user_secrets s ON u.email = 'test********.com' WHERE u.id = 1";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }
    }

    @Nested
    class WhereClauseVariations {
        @Test
        void testComparisonOperator_NotEquals() {
            String sql = "SELECT * FROM users WHERE password != 'secret'";
            String expected = "SELECT * FROM users WHERE password != 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testComparisonOperator_EqualsReversed() {
            String sql = "SELECT * FROM users WHERE 'secret' = password";
            String expected = "SELECT * FROM users WHERE 's****t' = password";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testComparisonOperator_GreaterThan() {
            String sql = "SELECT * FROM users WHERE password > 'secret'";
            String expected = "SELECT * FROM users WHERE password > 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testComparisonOperator_GreaterThanEquals() {
            String sql = "SELECT * FROM users WHERE password >= 'secret'";
            String expected = "SELECT * FROM users WHERE password >= 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testComparisonOperator_LessThan() {
            String sql = "SELECT * FROM users WHERE password < 'secret'";
            String expected = "SELECT * FROM users WHERE password < 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testComparisonOperator_LessThanEquals() {
            String sql = "SELECT * FROM users WHERE password <= 'secret'";
            String expected = "SELECT * FROM users WHERE password <= 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testNotOperator() {
            String sql = "SELECT * FROM users WHERE NOT password = 'secret'";
            String expected = "SELECT * FROM users WHERE NOT password = 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testLikeOperator() {
            String sql = "SELECT * FROM users WHERE password LIKE '%secret%'";
            String expected = "SELECT * FROM users WHERE password LIKE '%s****t%'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testLike_withEscape() throws Exception {
            String sql = "SELECT * FROM users WHERE `password` LIKE '!%secret%' ESCAPE '!'";
            String expected = "SELECT * FROM users WHERE `password` LIKE '!%****et%' ESCAPE '!'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testBetweenOperator() {
            String sql = "SELECT * FROM users WHERE password BETWEEN 'secret1' AND 'secret2'";
            String expected = "SELECT * FROM users WHERE password BETWEEN 'se****1' AND 'se****2'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testInOperator() {
            String sql = "SELECT * FROM users WHERE password IN ('secret1', 'secret2')";
            String expected = "SELECT * FROM users WHERE password IN ('se****1', 'se****2')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testInOperator_WithSubquery() {
            String sql = "SELECT * FROM users WHERE password IN (SELECT password FROM old_passwords WHERE user_id = 1 AND password = 'old_secret')";
            String expected = "SELECT * FROM users WHERE password IN (SELECT password FROM old_passwords WHERE user_id = 1 AND password = 'old****ret')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testSubquery() {
            String sql = "SELECT * FROM employees WHERE salary > (SELECT AVG(salary) FROM employees WHERE password = 'simple_password')";
            String expected = "SELECT * FROM employees WHERE salary > (SELECT AVG(salary) FROM employees WHERE password = 'simp*******word')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testExistsOperator() {
            String sql = "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM users WHERE users.customer_id = customers.id and users.password = 'simple_password')";
            String expected = "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM users WHERE users.customer_id = customers.id AND users.password = 'simp*******word')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }
    }

    @Nested
    class FieldIdentification {
        @Test
        void testSimpleFieldName() {
            String sql = "UPDATE users SET password = 'new_password'";
            String expected = "UPDATE users SET password = 'new_****word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testFieldNameWithTableAlias() {
            String sql = "SELECT u.password FROM users u WHERE u.password = 'secret'";
            String expected = "SELECT u.password FROM users u WHERE u.password = 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testFieldNameWithDatabasePrefix() {
            String sql = "SELECT * FROM db1.users WHERE db1.users.password = 'secret'";
            String expected = "SELECT * FROM db1.users WHERE db1.users.password = 's****t'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testFuzzyFieldNameMatch() {
            String sql = "UPDATE users SET app_password = 'new_password'";
            String expected = "UPDATE users SET app_password = 'new_****word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testQuotedFieldName() {
            String sql = "UPDATE users SET `password` = 'new_password'";
            String expected = "UPDATE users SET `password` = 'new_****word'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testFieldValueInFunction_unsupported() {
            String sql = "UPDATE users SET password = MD5('new_password')";
            String expected = "UPDATE users SET password = MD5('new_password')";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testFieldNameInFunction_unsupported() {
            String sql = "SELECT * FROM users WHERE LOWER(password) = 'secret'";
            String expected = "SELECT * FROM users WHERE LOWER(password) = 'secret'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testNotStringField_unsupported() {
            String sql = "SELECT * FROM users WHERE credit_card_no = 1234567890";
            String expected = "SELECT * FROM users WHERE credit_card_no = 1234567890";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));

            sql = "SELECT * FROM users WHERE credit_card_no = '1234567890'";
            expected = "SELECT * FROM users WHERE credit_card_no = '123****890'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }


    }

    @Nested
    class LikePatternMaskTests {

        @Test
        void testBasicLikePattern() {
            // 测试前缀%
            assertEquals("%s****t", sqlMasker.maskLikeValue("%secret", ""));
            // 测试后缀%
            assertEquals("s****t%", sqlMasker.maskLikeValue("secret%", ""));
            // 测试前后缀%
            assertEquals("%s****t%", sqlMasker.maskLikeValue("%secret%", ""));
            // 测试多个前缀%
            assertEquals("%%%s****t", sqlMasker.maskLikeValue("%%%secret", ""));
            // 测试多个后缀%
            assertEquals("s****t%%%", sqlMasker.maskLikeValue("secret%%%", ""));
        }

        @Test
        void testLikePatternWithUnderscore() {
            // 测试前缀_
            assertEquals("_s****t", sqlMasker.maskLikeValue("_secret", ""));
            // 测试后缀_
            assertEquals("s****t_", sqlMasker.maskLikeValue("secret_", ""));
            // 测试前后缀_
            assertEquals("_s****t_", sqlMasker.maskLikeValue("_secret_", ""));
            // 测试多个前缀_
            assertEquals("___s****t", sqlMasker.maskLikeValue("___secret", ""));
            // 测试多个后缀_
            assertEquals("s****t___", sqlMasker.maskLikeValue("secret___", ""));
        }

        @Test
        void testLikePatternWithMixedWildcards() {
            // 测试%和_混合
            assertEquals("%_s****t", sqlMasker.maskLikeValue("%_secret", ""));
            assertEquals("s****t_%", sqlMasker.maskLikeValue("secret_%", ""));
            assertEquals("%_s****t_%", sqlMasker.maskLikeValue("%_secret_%", ""));
            // 测试多个混合通配符
            assertEquals("%_%_s****t", sqlMasker.maskLikeValue("%_%_secret", ""));
            assertEquals("s****t_%_%", sqlMasker.maskLikeValue("secret_%_%", ""));
            assertEquals("%_%_s****t_%_%", sqlMasker.maskLikeValue("%_%_secret_%_%", ""));
        }

        @Test
        void testLikePatternEdgeCases() {
            // 测试空值
            assertNull(sqlMasker.maskLikeValue(null, ""));
            // 测试空字符串
            assertEquals("", sqlMasker.maskLikeValue("", ""));
            // 测试只有通配符
            assertEquals("%", sqlMasker.maskLikeValue("%", ""));
            assertEquals("_", sqlMasker.maskLikeValue("_", ""));
            assertEquals("%_%", sqlMasker.maskLikeValue("%_%", ""));
            // 测试全是通配符的情况
            assertEquals("%%%", sqlMasker.maskLikeValue("%%%", ""));
            assertEquals("___", sqlMasker.maskLikeValue("___", ""));
            assertEquals("%_%_%", sqlMasker.maskLikeValue("%_%_%", ""));
        }

        @Test
        void testLongLikePattern() {
            // 测试长字符串
            String longSecret = "ThisIsAVeryLongSecretValue";
            String maskedLong = sqlMasker.maskLikeValue("%" + longSecret + "%", "");
            assertTrue(maskedLong.startsWith("%This"));
            assertTrue(maskedLong.endsWith("alue%"));
            assertTrue(maskedLong.substring(5, maskedLong.length() - 5).matches("\\*+"));

            // 测试超长字符串
            String veryLongSecret = "ThisIsAnExtremelyLongSecretValueThatShouldBeMaskedProperly";
            String maskedVeryLong = sqlMasker.maskLikeValue("%" + veryLongSecret + "%", "");
            assertTrue(maskedVeryLong.startsWith("%This"));
            assertTrue(maskedVeryLong.endsWith("erly%"));
            assertTrue(maskedVeryLong.substring(5, maskedVeryLong.length() - 5).matches("\\*+"));
        }

        @Test
        void testLikePatternWithEscape() {
            // 测试转义%字符
            assertEquals("!%****et", sqlMasker.maskLikeValue("!%secret", "!"));
            assertEquals("se****!%", sqlMasker.maskLikeValue("secret!%", "!"));
            assertEquals("!%s****t!%", sqlMasker.maskLikeValue("!%secret!%", "!"));

            // 测试转义_字符
            assertEquals("!_****et", sqlMasker.maskLikeValue("!_secret", "!"));
            assertEquals("se****!_", sqlMasker.maskLikeValue("secret!_", "!"));
            assertEquals("!_s****t!_", sqlMasker.maskLikeValue("!_secret!_", "!"));

            // 测试转义字符本身
            assertEquals("!!s****t!", sqlMasker.maskLikeValue("!!secret!", "!"));
            assertEquals("se****!!", sqlMasker.maskLikeValue("secret!!", "!"));
            assertEquals("!!****et", sqlMasker.maskLikeValue("!!secret", "!"));

            // 测试混合转义情况
            assertEquals("!%!****ret", sqlMasker.maskLikeValue("!%!_secret", "!"));
            assertEquals("sec****_!%", sqlMasker.maskLikeValue("secret!_!%", "!"));
            assertEquals("!%!_******!_!%", sqlMasker.maskLikeValue("!%!_secret!_!%", "!"));

            // 测试转义字符和通配符混合
            assertEquals("%!%****et", sqlMasker.maskLikeValue("%!%secret", "!"));
            assertEquals("sec****!%", sqlMasker.maskLikeValue("secret%!%", "!"));
            assertEquals("%!%se****%!%", sqlMasker.maskLikeValue("%!%secret%!%", "!"));
            assertEquals("_!_se****_!_", sqlMasker.maskLikeValue("_!_secret_!_", "!"));

            // 测试复杂的转义和通配符组合
            assertEquals("!%se****%!%", sqlMasker.maskLikeValue("!%secret%!%", "!"));
            assertEquals("%!%s****t!%", sqlMasker.maskLikeValue("%!%secret!%", "!"));
            assertEquals("!%se****!%!%", sqlMasker.maskLikeValue("!%secret!%!%", "!"));
            assertEquals("!%!****ret%", sqlMasker.maskLikeValue("!%!%secret%", "!"));

            // 测试不同的转义字符
            assertEquals("#%****et", sqlMasker.maskLikeValue("#%secret", "#"));
            assertEquals("se****#%", sqlMasker.maskLikeValue("secret#%", "#"));
            assertEquals("#_s****t#_", sqlMasker.maskLikeValue("#_secret#_", "#"));

            assertEquals("%#1****#%", sqlMasker.maskLikeValue("%#12345#%", "#"));
            assertEquals("****_", sqlMasker.maskLikeValue("#_#__", "#"));
            assertEquals("%****_", sqlMasker.maskLikeValue("%#_#__", "#"));
            assertEquals("****%", sqlMasker.maskLikeValue("#_#%%", "#"));
            assertEquals("****%%", sqlMasker.maskLikeValue("#_#%%%", "#"));
        }

        @Test
        void testEscapeCharIsWildcard() {
            // escapeChar = '%'. Input "%%sec".
            // Expected: "%%" is literal. Treated as content.
            // "%%sec" (len 5) -> "%****" (with maskPattern "****")
            assertEquals("%****", sqlMasker.maskLikeValue("%%sec", "%"));
        }

        @Test
        void testDoubleEscapeAtEnd() {
            // escapeChar = '!'. Input "secret!!%".
            // Expected: "!!" is literal. "%" is wildcard.
            // "secret!!" (len 8) -> "se****!!"
            // Result: "se****!!%"
            assertEquals("se****!!%", sqlMasker.maskLikeValue("secret!!%", "!"));
        }

        @Test
        void testEscapeCharIsWildcardAndEscaped() {
            // escapeChar = '%'. Input "secret%%%".
            // Expected: "%%" is literal. Last "%" is wildcard.
            // "secret%%" (len 8) -> "se****%%"
            // Result: "se****%%%"
            assertEquals("se****%%%", sqlMasker.maskLikeValue("secret%%%", "%"));
        }

    }

    @Nested
    class EscapeCharacterHandling {
        @Test
        void testStandardSqlEscape() {
            String sql = "UPDATE users SET password = 'It''s_secret'";
            String expected = "UPDATE users SET password = 'It''****cret'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testMySqlEscape() {
            String sql = "UPDATE users SET password = 'It\\'s_secret'";
            String expected = "UPDATE users SET password = 'It\\'****cret'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testBackslashEscape() {
            String sql =      "UPDATE users SET password = 'Ba\\\\ck1234slash'";
            String expected = "UPDATE users SET password = 'Ba\\\\*******lash'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }

        @Test
        void testMixedEscape() {
            String sql =      "UPDATE users SET password = 'I\\'t\\\\s_a__test'";
            String expected = "UPDATE users SET password = 'I\\'t*******test'";
            assertEquals(expected, sqlMasker.maskSensitiveFields(sql));
        }
    }

    @Test
    void testMaskField() {
        // 1. 空值和边界情况
        assertNull(SqlMasker.doValueMask(null, "****"));
        assertEquals("123", SqlMasker.doValueMask("123", null));
        assertEquals("123", SqlMasker.doValueMask("123", ""));
        assertEquals("####", SqlMasker.doValueMask("", "####"));

        // 2. 规则1：字段长度 <= 掩码长度
        assertEquals("*", SqlMasker.doValueMask("1", "*"));
        assertEquals("##", SqlMasker.doValueMask("1", "##"));
        assertEquals("##", SqlMasker.doValueMask("12", "##"));
        assertEquals("###", SqlMasker.doValueMask("12", "###"));
        assertEquals("###", SqlMasker.doValueMask("123", "###"));
        assertEquals("****", SqlMasker.doValueMask("123", "****"));
        assertEquals("****", SqlMasker.doValueMask("1234", "****"));
        assertEquals("●●●●●", SqlMasker.doValueMask("1234", "●●●●●"));
        assertEquals("●●●●●", SqlMasker.doValueMask("12345", "●●●●●"));
        assertEquals("??????", SqlMasker.doValueMask("12345", "??????"));
        assertEquals("??????", SqlMasker.doValueMask("123456", "??????"));

        // 3. 规则2：字段长度在掩码长度1-3倍之间, i.e. 1x < fieldLength <= 3x
        // 3.1 偶数长度掩码模式（4个字符）的情况
        assertEquals("1****", SqlMasker.doValueMask("12345", "****"));               // 1x+1，首1尾0
        assertEquals("1****6", SqlMasker.doValueMask("123456", "****"));             // 1.5倍，剩余2个字符平均分配
        assertEquals("12####78", SqlMasker.doValueMask("12345678", "####"));         // 2倍，首2尾2
        assertEquals("123####89", SqlMasker.doValueMask("123456789", "####"));       // 2.25倍，剩余5个字符，首3末2
        assertEquals("1234????901", SqlMasker.doValueMask("12345678901", "????"));   // 2.75倍，剩余7个字符，首4末3
        assertEquals("1234????9012", SqlMasker.doValueMask("123456789012", "????")); // 3倍，首尾各4个字符

        // 3.2 奇数长度掩码模式（3个字符）的情况
        assertEquals("1***", SqlMasker.doValueMask("1234", "***"));               // 1x+1，首1尾0
        assertEquals("1***5", SqlMasker.doValueMask("12345", "***"));             // 1.67倍，剩余2个字符平均分配
        assertEquals("12###6", SqlMasker.doValueMask("123456", "###"));           // 2倍，首2末1
        assertEquals("12###67", SqlMasker.doValueMask("1234567", "###"));         // 2.33倍，剩余4个字符，首2末2
        assertEquals("123●●●78", SqlMasker.doValueMask("12345678", "●●●"));       // 2.67倍，剩余5个字符，首3末2
        assertEquals("123●●●789", SqlMasker.doValueMask("123456789", "●●●"));     // 3倍，首尾各3个字符

        // 4. 规则3：字段长度大于掩码长度3倍
        // 4.1 偶数长度掩码模式
        assertEquals("1234*?#●*0123", SqlMasker.doValueMask("1234567890123", "*?#●"));     // 3x+1，首4尾4，中间5个mask字符
        assertEquals("1234?●#*?●#2345", SqlMasker.doValueMask("123456789012345", "?●#*")); // 3x+3，首尾各4个字符，中间7个mask字符
        assertEquals("1234●?*#●?*#●4567", SqlMasker.doValueMask("12345678901234567", "●?*#")); // 4x+1，首尾各4个字符，中间9个mask字符

        // 4.2 奇数长度掩码模式
        assertEquals("123#?*#890", SqlMasker.doValueMask("1234567890", "#?*"));     // 3x+1，首尾各3个字符，中间4个mask字符
        assertEquals("12345●#?*#●#34567", SqlMasker.doValueMask("12345678901234567", "●#?*#")); // 3x+2，首尾各5个字符，中间7个mask字符
        assertEquals("123#?*#?*#123", SqlMasker.doValueMask("1234567890123", "#?*"));     // 4x+1，首尾各3个字符，中间7个mask字符

        // 4.3 中间填充长度整除掩码长度
        assertEquals("1234********3456", SqlMasker.doValueMask("1234567890123456", "****")); // 4x, midLen=8, pLen=4

        // 5. 特殊字符处理
        assertEquals("1 2 XXXX5 6", SqlMasker.doValueMask("1 2 3 4 5 6", "XXXX"));
        assertEquals("1\t2?????5\t6", SqlMasker.doValueMask("1\t2\t3\t4\t5\t6", "???"));
        assertEquals("    ", SqlMasker.doValueMask("123", "    "));
        assertEquals("    ", SqlMasker.doValueMask("1234", "    "));

        // 6. Unicode字符处理
        assertEquals("1####", SqlMasker.doValueMask("1你好世界", "####"));
        assertEquals("1○○○界", SqlMasker.doValueMask("1你好世界", "○○○"));
    }

    @Nested
    class ExceptionTest {
        /**
         * Covers: JSQLParserException catch block in maskSensitiveFields
         */
        @Test
        void testSqlParsingException() {
            // SQL must contain a sensitive field to pass the initial check
            // and be invalid to trigger JSQLParserException
            String invalidSql = "This is not a valid SQL but contains password";

            // Should return original SQL when parsing fails
            assertEquals(invalidSql, sqlMasker.maskSensitiveFields(invalidSql));
        }

        @Test
        void testNullSensitiveFields() {
            String sql = "INSERT INTO users (password) VALUES ('secret')";
            // Should return original SQL because sensitiveFields is null (optimization check returns false)
            assertEquals(sql, SqlMasker.maskSensitiveFields(sql, null, "****"));
        }

        @Test
        void testNullSql() {
            assertNull(sqlMasker.maskSensitiveFields(null));
        }

        /**
         * 解析失败兜底：返回值不含原字面值，WARN 不含原 SQL / 异常 message，只有异常类名
         */
        @Test
        void testParseFailureMasksAllLiteralsAndLogsNoSql() {
            String invalidSql = "UPDATE users SET password = 'secret123', note = 'It''s \\' ok' WHERE ((( id = 'x1";
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqlMasker.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            ch.qos.logback.classic.Level oldLevel = logger.getLevel();
            logger.setLevel(ch.qos.logback.classic.Level.WARN);
            try {
                String masked = sqlMasker.maskSensitiveFields(invalidSql);
                assertEquals("UPDATE users SET password = '****', note = '****' WHERE ((( id = '****", masked);

                assertEquals(1, appender.list.size());
                ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.get(0);
                assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
                String msg = event.getFormattedMessage();
                assertTrue(msg.contains("JSQLParserException"), msg);
                assertFalse(msg.contains("secret123"), msg);
                assertFalse(msg.contains("UPDATE"), msg);
                assertNull(event.getThrowableProxy());
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(oldLevel);
            }
        }

        @Test
        void testMaskAllStringLiterals() {
            assertEquals("a = '##' and b = '##'", SqlMasker.maskAllStringLiterals("a = 'x' and b = 'y'", "##"));
            // '' 转义引号、\ 转义
            assertEquals("a = '##' x", SqlMasker.maskAllStringLiterals("a = 'It''s' x", "##"));
            assertEquals("a = '##' x", SqlMasker.maskAllStringLiterals("a = 'It\\'s' x", "##"));
            assertEquals("a = '##', '##'", SqlMasker.maskAllStringLiterals("a = 'c:\\\\', 'y'", "##"));
            // 空字面值
            assertEquals("a = '##'", SqlMasker.maskAllStringLiterals("a = ''", "##"));
            // 未闭合掩到结尾（含以 \ 结尾）
            assertEquals("a = '##", SqlMasker.maskAllStringLiterals("a = 'abc", "##"));
            assertEquals("a = '##", SqlMasker.maskAllStringLiterals("a = 'abc\\", "##"));
            // 无字面值原样
            assertEquals("select 1", SqlMasker.maskAllStringLiterals("select 1", "##"));
            // maskPattern 为 null 时字面值清空
            assertEquals("a = ''", SqlMasker.maskAllStringLiterals("a = 'x'", null));
        }
    }

    /**
     * 全局首尾保留规则 mask-keep-head / mask-keep-tail
     */
    @Nested
    class GlobalKeepRule {
        @Test
        void testTailOnly() {
            SqlMasker masker = new SqlMasker("****", sensitiveFields, -1, 4, false);
            assertEquals("UPDATE users SET password = '****7890'",
                    masker.maskSensitiveFields("UPDATE users SET password = '6222021234567890'"));
            // 长度 <= head + tail 时整值输出 maskPattern
            assertEquals("UPDATE users SET password = '****'",
                    masker.maskSensitiveFields("UPDATE users SET password = '7890'"));
            assertEquals("UPDATE users SET password = '****'",
                    masker.maskSensitiveFields("UPDATE users SET password = 'abc'"));
        }

        @Test
        void testHeadOnlyAndBoth() {
            SqlMasker headOnly = new SqlMasker("****", sensitiveFields, 2, -1, false);
            assertEquals("UPDATE users SET password = 'se****'",
                    headOnly.maskSensitiveFields("UPDATE users SET password = 'secret123'"));
            SqlMasker both = new SqlMasker("**", sensitiveFields, 1, 2, false);
            assertEquals("UPDATE users SET password = 's**23'",
                    both.maskSensitiveFields("UPDATE users SET password = 'secret123'"));
            assertEquals("UPDATE users SET password = '**'",
                    both.maskSensitiveFields("UPDATE users SET password = 'abc'"));
        }

        @Test
        void testZeroZeroMasksWholeValue() {
            SqlMasker masker = new SqlMasker("****", sensitiveFields, 0, 0, false);
            assertEquals("UPDATE users SET password = '****'",
                    masker.maskSensitiveFields("UPDATE users SET password = 'secret123456789'"));
            assertEquals("UPDATE users SET password = '****'",
                    masker.maskSensitiveFields("UPDATE users SET password = ''"));
        }

        @Test
        void testLikeUsesGlobalRule() {
            SqlMasker masker = new SqlMasker("****", sensitiveFields, -1, 4, false);
            assertEquals("SELECT * FROM users WHERE password LIKE '%****7890%'",
                    masker.maskSensitiveFields("SELECT * FROM users WHERE password LIKE '%6222021234567890%'"));
        }

        @Test
        void testDoKeepMask() {
            assertNull(SqlMasker.doKeepMask(null, "****", 0, 4));
            assertEquals("123", SqlMasker.doKeepMask("123", null, 0, 0));
            assertEquals("123", SqlMasker.doKeepMask("123", "", 0, 0));
            assertEquals("12##90", SqlMasker.doKeepMask("1234567890", "##", 2, 2));
            assertEquals("##", SqlMasker.doKeepMask("1234", "##", 2, 2));
            assertEquals("1234##", SqlMasker.doKeepMask("1234567890", "##", 4, 0));
        }
    }

    /**
     * 按列后缀 列名:首:尾
     */
    @Nested
    class PerColumnKeepRule {
        private final Set<String> fields = new HashSet<>(Arrays.asList(
                "bank_account:0:4", " PIN : 0 : 0 ", "password", "pin"));

        @Test
        void testSuffixOverridesDefaultAlgorithm() {
            SqlMasker masker = new SqlMasker("****", fields);
            assertEquals("UPDATE t SET bank_account = '****7890', pin = '****', password = 'sec****23'",
                    masker.maskSensitiveFields(
                            "UPDATE t SET bank_account = '6222021234567890', pin = '123456', password = 'secret123'"));
        }

        @Test
        void testSuffixOverridesGlobalRule() {
            SqlMasker masker = new SqlMasker("****", fields, 2, -1, false);
            assertEquals("UPDATE t SET bank_account = '****7890', pin = '****', password = 'se****'",
                    masker.maskSensitiveFields(
                            "UPDATE t SET bank_account = '6222021234567890', pin = '123456', password = 'secret123'"));
        }

        @Test
        void testLikeUsesColumnRule() {
            SqlMasker masker = new SqlMasker("****", fields);
            assertEquals("SELECT * FROM t WHERE bank_account LIKE '%****7890_'",
                    masker.maskSensitiveFields("SELECT * FROM t WHERE bank_account LIKE '%6222021234567890_'"));
            assertEquals("SELECT * FROM t WHERE PIN LIKE '****%'",
                    masker.maskSensitiveFields("SELECT * FROM t WHERE PIN LIKE '123456%'"));
        }

        @Test
        void testMultipleMatchesPreferEqualThenLongest() {
            SqlMasker masker = new SqlMasker("**", new HashSet<>(Arrays.asList(
                    "account:1:1", "bank_account:0:4", "ab:0:1", "bc:1:0")), -1, -1, false);
            // 与列名相等的优先
            assertEquals("UPDATE t SET account = '1**0'",
                    masker.maskSensitiveFields("UPDATE t SET account = '1234567890'"));
            assertEquals("UPDATE t SET `account` = '1**0'",
                    masker.maskSensitiveFields("UPDATE t SET `account` = '1234567890'"));
            assertEquals("UPDATE t SET bank_account = '**7890'",
                    masker.maskSensitiveFields("UPDATE t SET bank_account = '1234567890'"));
            // 没有相等的取最长
            assertEquals("UPDATE t SET bank_account_no = '**7890'",
                    masker.maskSensitiveFields("UPDATE t SET bank_account_no = '1234567890'"));
            // 等长时取字典序小的（ab）
            assertEquals("UPDATE t SET abc = '**0'",
                    masker.maskSensitiveFields("UPDATE t SET abc = '1234567890'"));
        }

        @Test
        void testIllegalSuffixFailsFast() {
            for (String bad : new String[] {"pin:a:0", "pin:-1:0", "pin:0", "pin:0:0:0", ":0:4", "pin::4",
                    "pin:99999999999:0", "pin:0:4.5"}) {
                Set<String> set = new HashSet<>(Arrays.asList("password", bad));
                assertThrows(IllegalArgumentException.class, () -> new SqlMasker("****", set), bad);
            }
            // 同一列给了不同的后缀
            assertThrows(IllegalArgumentException.class,
                    () -> new SqlMasker("****", new HashSet<>(Arrays.asList("pin:0:0", "PIN:0:4"))));
            // 同一列相同后缀 / 后缀 + 无后缀 不冲突
            assertDoesNotThrow(() -> new SqlMasker("****", new HashSet<>(Arrays.asList("pin:0:0", "PIN:0:0", "pin"))));
        }
    }

    /**
     * 精确列名匹配 sensitive-fields-exact-match
     */
    @Nested
    class ExactMatch {
        private final Set<String> fields = new HashSet<>(Arrays.asList("response_body", "pin:0:0"));

        @Test
        void testExactMatchDoesNotHitLongerColumn() {
            SqlMasker masker = new SqlMasker("****", fields, -1, -1, true);
            // 快速检查即可排除（response_body 后接 _ 不是标识符边界）
            String sql = "UPDATE t SET response_body_excerpt = 'abcdefghij'";
            assertEquals(sql, masker.maskSensitiveFields(sql));
            sql = "UPDATE t SET response_body$x = 'abcdefghij'";
            assertEquals(sql, masker.maskSensitiveFields(sql));
            // 快速检查命中（字面值里有 response_body），但列名不相等，不掩
            sql = "UPDATE t SET response_body_excerpt = 'abcdefghij', note = 'response_body'";
            assertEquals(sql, masker.maskSensitiveFields(sql));
        }

        @Test
        void testExactMatchHitsQuotedAndQualifiedColumn() {
            SqlMasker masker = new SqlMasker("****", fields, -1, -1, true);
            assertEquals("UPDATE t SET `response_body` = 'abc****hij'",
                    masker.maskSensitiveFields("UPDATE t SET `response_body` = 'abcdefghij'"));
            assertEquals("SELECT * FROM t WHERE \"RESPONSE_BODY\" = 'abc****hij'",
                    masker.maskSensitiveFields("SELECT * FROM t WHERE \"RESPONSE_BODY\" = 'abcdefghij'"));
            assertEquals("SELECT * FROM t WHERE t.response_body = 'abc****hij' AND pin = '****'",
                    masker.maskSensitiveFields("SELECT * FROM t WHERE t.response_body = 'abcdefghij' AND pin = '1234'"));
        }

        @Test
        void testSubstringModeUnchanged() {
            SqlMasker masker = new SqlMasker("****", fields, -1, -1, false);
            assertEquals("UPDATE t SET response_body_excerpt = 'abc****hij'",
                    masker.maskSensitiveFields("UPDATE t SET response_body_excerpt = 'abcdefghij'"));
        }

        @Test
        void testStripIdentifierQuotes() {
            assertEquals("pin", SqlMasker.stripIdentifierQuotes("[pin]"));
            assertEquals("pin", SqlMasker.stripIdentifierQuotes("`pin`"));
            assertEquals("pin", SqlMasker.stripIdentifierQuotes("\"pin\""));
            assertEquals("", SqlMasker.stripIdentifierQuotes("``"));
            assertEquals("a`b", SqlMasker.stripIdentifierQuotes("a`b"));
        }
    }

}
