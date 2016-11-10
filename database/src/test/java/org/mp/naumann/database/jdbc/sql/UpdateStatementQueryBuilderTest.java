package org.mp.naumann.database.jdbc.sql;

import org.junit.Test;
import org.mp.naumann.database.jdbc.sql.helper.UpdateStatements;
import org.mp.naumann.database.statement.UpdateStatement;

import java.util.Arrays;
import java.util.List;

import static junit.framework.TestCase.assertEquals;

public class UpdateStatementQueryBuilderTest {

    UpdateStatementQueryBuilder usqb = UpdateStatementQueryBuilder.get();

    @Test
    public void testGenerateSingle(){
        String expected = "UPDATE people SET name = 'max', age = '15' WHERE name = 'hanna' AND age = '12';";
        assertEquals(expected, usqb.generateSingle(UpdateStatements.createUpdateStatement1()));
    }

    @Test
    public void testGenerateMulti(){
        String expected = "UPDATE people SET name = 'max', age = '15' WHERE name = 'hanna' AND age = '12';\n" +
                "UPDATE places SET country = 'US', city = 'San Francisco' WHERE country = 'DE' AND city = 'Berlin';";

        List<UpdateStatement> statements = Arrays.asList(
                UpdateStatements.createUpdateStatement1(),
                UpdateStatements.createUpdateStatementDifferentTable()
        );

        assertEquals(expected, usqb.generateMulti(statements));
    }
}