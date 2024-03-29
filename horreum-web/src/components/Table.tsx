import { useEffect, useState } from "react"
import { Bullseye, Skeleton, Spinner } from "@patternfly/react-core"
import { Table as PfTable, Thead, Tr, Th, Tbody, Td, ThProps } from '@patternfly/react-table';
import {
    useTable,
    useSortBy,
    useRowSelect,
    Column,
    SortingRule,
    TableState,
    UseRowSelectRowProps,
    UseRowSelectState,
    UseSortByState,
    UseSortByColumnProps,
} from "react-table"
import clsx from "clsx"

import { noop } from "../utils"

type Direction = 'asc' | 'desc' | undefined;

// We need to pass the same empty list to prevent re-renders
const NO_DATA: Record<string, unknown>[] = []
const NO_SORT: SortingRule<any>[] = []

// eslint-disable-next-line @typescript-eslint/ban-types
type TableProps<D extends object> = {
    columns: Column<D>[]
    data: D[]
    sortBy: SortingRule<D>[]
    isLoading: boolean
    selected: Record<string, boolean>
    onSelected(ids: Record<string, boolean>): void
    onSortBy?(order: SortingRule<D>[]): void
    showNumberOfRows?: boolean
}

// FIXME: Default values in parameters doesn't work: https://github.com/microsoft/TypeScript/issues/31247
const defaultProps = {
    sortBy: NO_SORT,
    isLoading: false,
    selected: NO_DATA,
    onSelected: noop,
}

// eslint-disable-next-line @typescript-eslint/ban-types
function Table<D extends object>({
    columns,
    data,
    sortBy,
    isLoading,
    selected,
    onSelected,
    onSortBy,
    ...props
}: TableProps<D>) {
    const [activeSortIndex, setActiveSortIndex] = useState<number | undefined>(undefined);
    const [activeSortDirection, setActiveSortDirection] = useState<Direction>(undefined);
    const [currentSortBy, setCurrentSortBy] = useState(sortBy)

    const { getTableProps, getTableBodyProps, headerGroups, rows, prepareRow, state } = useTable<D>(
        {
            columns,
            data: data || NO_DATA,
            initialState: {
                sortBy: currentSortBy,
                selectedRowIds: selected,
            } as TableState<D>,
        },
        useSortBy,
        useRowSelect
    )

    const getSortParams = (columnIndex: number): ThProps['sort'] => ({
        sortBy: {
            index: activeSortIndex ?? undefined,
            direction: activeSortDirection ?? undefined
        },
        onSort: (_event, index, direction) => {
            // The order is asc -> desc -> reset
            if (index === activeSortIndex && activeSortDirection === 'desc' && direction === 'asc') {
                setActiveSortIndex(undefined)
                setActiveSortDirection(undefined)
            } else {
                setActiveSortIndex(index);
                setActiveSortDirection(direction as 'desc' | 'asc');
            }
        },
        columnIndex
      });

    useEffect(() => {
        setCurrentSortBy(sortBy)
    }, [sortBy])

    const rsState = state as UseRowSelectState<D>
    useEffect(() => {
        onSelected(rsState.selectedRowIds)
    }, [rsState.selectedRowIds, onSelected])

    const sortState = state as UseSortByState<D>
    useEffect(() => {
        setCurrentSortBy(sortState.sortBy)
        if (onSortBy && sortState.sortBy) {
            onSortBy(sortState.sortBy)
        }
    }, [sortState.sortBy, onSortBy])

    if (isLoading) {
        return (
            <PfTable variant="compact" {...getTableProps()}>
                <Thead>
                    <Tr>
                        <Th className={clsx("pf-v5-c-table__sort")}>
                            <button className="pf-v5-c-button pf-m-plain" type="button">
                                Loading... <Spinner size="sm" />
                            </button>
                        </Th>
                    </Tr>
                </Thead>
                <Tbody>
                    {[...Array(10).keys()].map(i => {
                        return (
                            <Tr key={i}>
                                <Td>
                                    <Skeleton screenreaderText="Loading..." />
                                </Td>
                            </Tr>
                        )
                    })}
                </Tbody>
            </PfTable>
        )
    }
    if (!data) {
        return (
            <Bullseye>
                <Spinner />
            </Bullseye>
        )
    }
    
    return (
        <>
            <PfTable variant="compact" {...getTableProps()}>
                <Thead>
                    {headerGroups.map(headerGroup => {
                            return (
                                <Tr {...headerGroup.getHeaderGroupProps()}>
                                    {headerGroup.headers.map((column, idx) => {
                                        const columnProps = column as unknown as UseSortByColumnProps<D>
                                        return (
                                            // Add the sorting props to control sorting. For this example
                                            // we can add them into the header props

                                            <Th
                                                className={clsx(
                                                    "pf-v5-c-table__sort",
                                                )}
                                                modifier="wrap"
                                                sort={columnProps.canSort ? getSortParams(idx) : undefined}
                                                {...column.getHeaderProps(columnProps.getSortByToggleProps())}
                                            >
                                                <span className="xpf-v5-c-table__text pf-m-plain">{column.render("Header")}</span>
                                            </Th>
                                        )
                                    })}
                                </Tr>
                            )
                        })}
                </Thead>
                <Tbody {...getTableBodyProps()}>
                    {rows.map(row => {
                        prepareRow(row)
                        const rowProps = row.getRowProps()
                        if ((row as unknown as UseRowSelectRowProps<D>).isSelected) {
                            rowProps.style = { ...rowProps.style, background: "#EEE" }
                        }
                        return (
                            <Tr {...rowProps}>
                                {row.cells.map(cell => {
                                    return (
                                        <Td data-label={cell.column.Header} {...cell.getCellProps()}>
                                            {cell.render("Cell")}
                                        </Td>
                                    )
                                })}
                            </Tr>
                        )
                    })}
                </Tbody>
            </PfTable>
            <br />
            {(props.showNumberOfRows === undefined || props.showNumberOfRows) && <div>Showing {rows.length} rows</div>}
        </>
    )
}
Table.defaultProps = defaultProps

export default Table
