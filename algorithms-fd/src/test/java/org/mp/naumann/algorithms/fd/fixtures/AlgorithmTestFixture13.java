package org.mp.naumann.algorithms.fd.fixtures;

import com.google.common.collect.ImmutableList;

import org.mp.naumann.algorithms.exceptions.CouldNotReceiveResultException;
import org.mp.naumann.algorithms.fd.FunctionalDependency;
import org.mp.naumann.database.TableInput;
import org.mp.naumann.database.data.ColumnCombination;
import org.mp.naumann.database.data.ColumnIdentifier;
import org.mp.naumann.database.data.GenericRow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AlgorithmTestFixture13 extends AbstractAlgorithmTestFixture {

    public AlgorithmTestFixture13() throws CouldNotReceiveResultException {
        this.columnNames = ImmutableList.of("A", "B", "C");
        this.numberOfColumns = 3;

        this.table.add(GenericRow.ofColumnNames(columnNames, "1", "2", "1"));
        this.table.add(GenericRow.ofColumnNames(columnNames, "1", "2", "2"));
        this.table.add(GenericRow.ofColumnNames(columnNames, "1", "2", "3"));


    }

    public TableInput getTableInput() {
        TableInput input = mock(TableInput.class);

        when(input.getColumnNames())
                .thenReturn(this.columnNames);
        when(input.numberOfColumns())
                .thenReturn(this.numberOfColumns);
        when(input.getName())
                .thenReturn(this.relationName);

        when(input.hasNext())
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.TRUE)
//			.thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);

        when(input.next())
                .thenReturn(this.table.get(0))
                .thenReturn(this.table.get(1))
                .thenReturn(this.table.get(2));
//			.thenReturn(this.table.get(3));

        return input;
    }

    public void verifyFunctionalDependencyResultReceiver() {
        ColumnIdentifier expectedIdentifierA = new ColumnIdentifier(this.relationName, this.columnNames.get(0));
        ColumnIdentifier expectedIdentifierB = new ColumnIdentifier(this.relationName, this.columnNames.get(1));

        verify(this.fdResultReceiver).receiveResult(new FunctionalDependency(new ColumnCombination(), expectedIdentifierA));
        verify(this.fdResultReceiver).receiveResult(new FunctionalDependency(new ColumnCombination(), expectedIdentifierB));

        verifyNoMoreInteractions(this.fdResultReceiver);
    }

}
