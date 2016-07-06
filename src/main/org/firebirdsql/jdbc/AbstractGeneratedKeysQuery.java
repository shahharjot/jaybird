/*
 * Firebird Open Source JavaEE Connector - JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.firebirdsql.jdbc.parser.JaybirdStatementModel;
import org.firebirdsql.jdbc.parser.StatementParser;
import org.firebirdsql.jdbc.parser.StatementParser.ParseException;
import org.firebirdsql.logging.Logger;
import org.firebirdsql.logging.LoggerFactory;

/**
 * Class to add the RETURNING clause to queries for returning generated keys.
 * 
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @since 2.2
 */
public abstract class AbstractGeneratedKeysQuery {

    // TODO Add caching for column info

    private static final Logger logger = LoggerFactory.getLogger(AbstractGeneratedKeysQuery.class);
    private static final int QUERY_TYPE_KEEP_UNMODIFIED = 1;
    private static final int QUERY_TYPE_ADD_ALL_COLUMNS = 2;
    private static final int QUERY_TYPE_ADD_INDEXED = 3;
    private static final int QUERY_TYPE_ADD_COLUMNS = 4;
    private static final int QUERY_TYPE_ALREADY_HAS_RETURNING = 5;
    private static final int IDX_COLUMN_NAME = 4;
    private static final int IDX_ORDINAL_POSITION = 17;

    private static final String GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE =
            "Generated keys functionality not available, most likely cause: ANTLR-Runtime not available on classpath";

    private static final StatementParser parser;
    static {
    	// Attempt to load statement parser
        StatementParser temp = null;
        try {
            temp = (StatementParser)Class.forName("org.firebirdsql.jdbc.parser.StatementParserImpl").newInstance();
        } catch (Throwable ex) {
            // Unable to load class of parser implementation, antlr-runtime not in path
            Logger log = LoggerFactory.getLogger(AbstractGeneratedKeysQuery.class);
            if (log != null) {
                log.error("Unable to load generated key parser. " + GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE , ex);
            }
        } finally {
            parser = temp;
        }
    }

    private final String originalSQL;
    private String modifiedSQL;
    private int queryType = QUERY_TYPE_KEEP_UNMODIFIED;
    private int[] columnIndexes;
    private String[] columnNames;
    private boolean processed = false;
    private boolean generatesKeys = false;
    private JaybirdStatementModel statementModel;

    private AbstractGeneratedKeysQuery(String sql) {
        originalSQL = sql;
    }

    /**
     * Process SQL statement text according to autoGeneratedKeys value.
     * <p>
     * For Statement.NO_GENERATED_KEYS the statement will not be processed, for
     * Statement.RETURN_GENERATED_KEYS it will be processed.
     * </p>
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param autoGeneratedKeys
     *            Valid values {@link java.sql.Statement#NO_GENERATED_KEYS} and
     *            {@link java.sql.Statement#RETURN_GENERATED_KEYS}
     * @throws SQLException
     *             If the supplied autoGeneratedKeys value does not match valid
     *             values or if the parser cannot be loaded when autoGeneratedKeys = RETURN_GENERATED_KEYS.
     */
    public AbstractGeneratedKeysQuery(String sql, int autoGeneratedKeys) throws SQLException {
        this(sql);
        
        switch (autoGeneratedKeys) {
        case Statement.RETURN_GENERATED_KEYS:
            if (!isGeneratedKeysSupportLoaded()) {
                throw new FBDriverNotCapableException(GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE);
            }
            queryType = QUERY_TYPE_ADD_ALL_COLUMNS;
            break;
        case Statement.NO_GENERATED_KEYS:
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
            break;
        default:
            throw new FBSQLException("Supplied value for autoGeneratedKeys is invalid",
                    SQLStateConstants.SQL_STATE_INVALID_OPTION_IDENTIFIER);
        }
    }

    /**
     * Process SQL statement for adding generated key columns by their ordinal
     * position.
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * <p>
     * The columns are added in ascending order of their index value, not by the
     * order of indexes in the columnIndexes array. The values of columnIndexes
     * are taken as the ORDINAL_POSITION returned by
     * {@link java.sql.DatabaseMetaData#getColumns(String, String, String, String)}
     * . When a column index does not exist for the table of the query, then it
     * will be discarded from the list silently.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param columnIndexes
     *            Array of ORDINAL_POSITION values of the columns to return as
     *            generated key
     * @throws SQLException If the parser cannot be loaded
     */
    public AbstractGeneratedKeysQuery(String sql, int[] columnIndexes) throws SQLException {
        this(sql);
        if (!isGeneratedKeysSupportLoaded()) {
            throw new FBDriverNotCapableException(GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE);
        } else if (columnIndexes != null && columnIndexes.length != 0) {
            this.columnIndexes = columnIndexes.clone();
            queryType = QUERY_TYPE_ADD_INDEXED;
        } else {
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
        }
    }

    /**
     * Process SQL statement for adding generated key columns by name.
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * <p>
     * The columnNames passed are taken as is and included in a new returning
     * clause. There is no check for actual existence of these columns, nor are
     * they quoted.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param columnNames
     *            Array of column names to return as generated key
     * @throws SQLException If the parser cannot be loaded
     */
    public AbstractGeneratedKeysQuery(String sql, String[] columnNames) throws SQLException {
        this(sql);
        if (!isGeneratedKeysSupportLoaded()) {
            throw new FBDriverNotCapableException(GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE);
        } else if (columnNames != null && columnNames.length != 0) {
            this.columnNames = columnNames.clone();
            queryType = QUERY_TYPE_ADD_COLUMNS;
        } else {
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
        }
    }

    /**
     * Indicates if the query will generate keys.
     * 
     * @return <code>true</code> if the query will generate keys,
     *         <code>false</code> otherwise
     * @throws SQLException
     *             For errors accessing the metadata
     */
    public boolean generatesKeys() throws SQLException {
        process();
        return generatesKeys;
    }

    /**
     * Returns the actual query.
     * <p>
     * Use {@link #generatesKeys()} to see if this query will in fact generate
     * keys.
     * </p>
     * 
     * @return The SQL query
     * @throws SQLException
     *             For errors accessing the metadata
     */
    public String getQueryString() throws SQLException {
        process();
        return modifiedSQL;
    }

    /**
     * Parses the query and updates the query with generated keys if
     * modifications are needed or possible.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void process() throws SQLException {
        if (processed) {
            return;
        }
        try {
            processStatementModel();
            updateQuery();
        } finally {
            processed = true;
        }
    }

    /**
     * Parses the original SQL query and checks if it already has a RETURNING
     * clause
     * @throws SQLException If query parsing is not available
     */
    private void processStatementModel() throws SQLException {
        if (!isGeneratedKeysSupportLoaded()) {
            if (queryType == QUERY_TYPE_KEEP_UNMODIFIED) {
                // JDBC specifies that NO_GENERATED_KEYS (signified here by QUERY_TYPE_KEEP_UNMODIFIED) 
            	// should not result in failure if processing generated keys is not possible
                return;
            } else {
                // This condition should already have been caught in constructors, but do it anyway:
                throw new FBDriverNotCapableException(GENERATED_KEYS_FUNCTIONALITY_NOT_AVAILABLE);
            }
        }
        try {
            statementModel = parseInsertStatement(originalSQL);
            if (statementModel.hasReturning()) {
                queryType = QUERY_TYPE_ALREADY_HAS_RETURNING;
            }
        } catch (ParseException e) {
            if (logger.isDebugEnabled()) logger.debug("Exception parsing query: " + originalSQL, e);
            // Unrecognized statement (so no INSERT, DELETE, UPDATE or UPDATE OR INSERT statement), keep as is
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
        }
    }

    /**
     * Adds the generated key columns to the query.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void updateQuery() throws SQLException {
        switch (queryType) {
        case QUERY_TYPE_ADD_ALL_COLUMNS:
            addAllColumns();
            break;
        case QUERY_TYPE_ADD_INDEXED:
            addIndexedColumns();
            break;
        case QUERY_TYPE_ADD_COLUMNS:
            addReturningClause();
            break;
        case QUERY_TYPE_ALREADY_HAS_RETURNING:
            generatesKeys = true;
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
            break;
        case QUERY_TYPE_KEEP_UNMODIFIED:
        	// Do nothing
        	break;
        default:
            throw new IllegalStateException("Unsupported value for queryType: " + queryType);
        }
        // Not part of switch: elements of switch will modify queryType (eg when
        // nothing is added)
        if (queryType == QUERY_TYPE_KEEP_UNMODIFIED) {
            modifiedSQL = originalSQL;
        }
    }

    /**
     * Adds all available table columns to the query as generated keys.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void addAllColumns() throws SQLException {
        DatabaseMetaData metaData = getDatabaseMetaData();
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, null, normalizeObjectName(statementModel.getTableName()), null)) {
            while (rs.next()) {
                // Need to quote columns for mixed case columns
                columns.add('"' + rs.getString(IDX_COLUMN_NAME) + '"');
            }
        }
        columnNames = columns.toArray(new String[0]);
        addReturningClause();
    }

    /**
     * Adds all columns referenced by columnIndexes to the query as generated
     * keys.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void addIndexedColumns() throws SQLException {
        DatabaseMetaData metaData = getDatabaseMetaData();
        Arrays.sort(columnIndexes);
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, null, normalizeObjectName(statementModel.getTableName()), null)) {
            while (rs.next()) {
                if (Arrays.binarySearch(columnIndexes, rs.getInt(IDX_ORDINAL_POSITION)) >= 0) {
                    // Need to quote columns for mixed case columns
                    columns.add('"' + rs.getString(IDX_COLUMN_NAME) + '"');
                }
            }
        }
        columnNames = columns.toArray(new String[0]);
        addReturningClause();
    }

    /**
     * Normalizes an object name from the parser.
     * <p>
     * Like-wildcard characters are escaped, and unquoted identifiers are uppercased, and quoted identifiers are
     * returned with the quotes stripped.
     * </p>
     *
     * @param objectName Object name
     * @return Normalized object name
     */
    private String normalizeObjectName(String objectName) {
        if (objectName == null) return null;
        objectName = objectName.trim();
        objectName = FBDatabaseMetaData.escapeWildcards(objectName);
        if (objectName.length() > 2 && objectName.charAt(0) == '"' && objectName.charAt(objectName.length() - 1) == '"') {
            return objectName.substring(1, objectName.length() - 1);
        }
        return objectName.toUpperCase();
    }

    /**
     * Adds the columns in columnNames to the query as generated keys.
     */
    private void addReturningClause() {
        if (columnNames == null || columnNames.length == 0) {
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
            return;
        }
        generatesKeys = true;

        StringBuilder query = new StringBuilder(originalSQL);
        if (query.charAt(query.length() - 1) == ';') {
            query.setLength(query.length() - 1);
        }
        query.append('\n');
        query.append("RETURNING ");
        for (int i = 0; i < columnNames.length; i++) {
            query.append(columnNames[i]);

            if (i < columnNames.length - 1) {
                query.append(',');
            }
        }

        modifiedSQL = query.toString();
    }

    /**
     * Returns the DatabaseMetaData object to be used when processing this
     * query. In general this should be a DatabaseMetaData object created from
     * the connection which will execute the query.
     * 
     * @return DatabaseMetaData object
     * @throws SQLException
     *             if a database access error occurs
     */
    abstract DatabaseMetaData getDatabaseMetaData() throws SQLException;

    /**
     * Parse the INSERT statement and extract the corresponding model.
     * 
     * @param sql
     *            SQL statement to parse.
     * 
     * @return instance of {@link JaybirdStatementModel}
     * @throws ParseException if statement cannot be parsed.
     */
    private JaybirdStatementModel parseInsertStatement(String sql) throws ParseException {
        return parser.parseInsertStatement(sql);
    }

    /**
     * Indicates if generated keys support has been loaded and available for use.
     * <p>
     * This method returns {@code false} when the antlr-runtime is not on the classpath or the {@link StatementParser}
     * implementation could not be loaded for other reasons.
     * </p>
     *
     * @return {@code true} if generated keys can be used in the driver (assuming the Firebird version supports it)
     */
    public static boolean isGeneratedKeysSupportLoaded() {
        return parser != null;
    }
}
