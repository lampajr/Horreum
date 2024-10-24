import { useState, useEffect, useContext } from "react"
import {
    Dropdown, DropdownItem,
    ExpandableSection, MenuToggle, MenuToggleElement,
    Tab,
    Tabs
} from '@patternfly/react-core';
import { CheckIcon } from "@patternfly/react-icons"
import { NavLink } from "react-router-dom"
import { alertingApi, Change, FingerprintValue, Variable } from "../../api"
import { fingerprintToString, formatDateTime } from "../../utils"
import { Column, UseSortByColumnOptions } from "react-table"
import { useTester } from "../../auth"
import { AppContext } from "../../context/appContext";
import { AppContextType } from "../../context/@types/appContextTypes";
import CustomTable from "../../components/CustomTable";

import { ChangeModal } from "./ChangeModal";
import EllipsisVIcon from "@patternfly/react-icons/dist/esm/icons/ellipsis-v-icon";

type C = Column<Change> & UseSortByColumnOptions<Change>

type ChangeMenuProps = {
    change: Change
    onDelete(id: number): void
    onUpdate(change: Change): void
}

export const ChangeMenu = ({ change, onDelete, onUpdate }: ChangeMenuProps) => {
    const [open, setOpen] = useState(false)
    const [modalChange, setModalChange] = useState<Change>()
    const onSelect = () => {
        setOpen(false);
    };

    return (
        <>
            <Dropdown
                onSelect={onSelect}
                onOpenChange={(isOpen: boolean) => setOpen(isOpen)}
                toggle={(toggleRef: React.Ref<MenuToggleElement>) => (
                    <MenuToggle ref={toggleRef} onClick={() => setOpen(!open)} isExpanded={open} variant="plain">
                        <EllipsisVIcon />
                    </MenuToggle>
                )}
                isOpen={open}
                popperProps={{position: "right"}}
            >
                {[
                    <DropdownItem
                        key="confirm"
                        isDisabled={change.confirmed}
                        onClick={() => {
                            setOpen(false)
                            setModalChange({ ...change, confirmed: true })
                        }}
                    >
                        Confirm
                    </DropdownItem>,
                    <DropdownItem
                        key="delete"
                        isDisabled={change.confirmed}
                        onClick={() => {
                            onDelete(change.id)
                            setOpen(false)
                        }}
                    >
                        Delete
                    </DropdownItem>,
                    <DropdownItem
                        key="update"
                        onClick={() => {
                            setOpen(false)
                            setModalChange(change)
                        }}
                    >
                        Edit
                    </DropdownItem>,
                ]}
            </Dropdown>
            <ChangeModal
                change={modalChange}
                isOpen={!!modalChange}
                onClose={() => setModalChange(undefined)}
                onUpdate={onUpdate}
            />
        </>
    )
}

type ChangesProps = {
    varId: number
    fingerprint: FingerprintValue | undefined
    testOwner?: string
    selectedChangeId?: number
}

export const ChangeTable = ({ varId, fingerprint, testOwner, selectedChangeId }: ChangesProps) => {
    const { alerting } = useContext(AppContext) as AppContextType;
    const [changes, setChanges] = useState<Change[]>([])
    useEffect(() => {
        alertingApi.changes(varId, fingerprintToString(fingerprint)).then(
            response => setChanges(response),
            error => alerting.dispatchError(error, "DASHBOARD_FETCH", "Failed to fetch dashboard")
        )
    }, [varId])
    const isTester = useTester(testOwner)
    const columns: C[] = [
        {
            Header: "Confirmed",
            id: "confirmed",
            accessor: "confirmed",
            Cell: (arg: any) => (arg.cell.value ? <CheckIcon id={"change_" + arg.row.original.id} /> : <></>),
        },
        {
            Header: "Time",
            id: "timestamp",
            accessor: "timestamp",
            sortType: "datetime",
            Cell: (arg: any) => <div>{formatDateTime(arg.cell.value)}</div>,
        },
        {
            Header: "Dataset",
            id: "dataset",
            accessor: "dataset",
            Cell: (arg: any) => {
                const dataset = arg.cell.value
                if (!dataset) return <></>
                return (
                    <NavLink to={`/run/${dataset.runId}#dataset${dataset.ordinal}`}>
                        {dataset.runId}/{dataset.ordinal}
                    </NavLink>
                )
            },
        },
        {
            Header: "Description",
            id: "description",
            accessor: "description",
            Cell: (arg: any) => <div dangerouslySetInnerHTML={{ __html: arg.cell.value }} />,
        },
    ]
    if (isTester) {
        columns.push({
            Header: "",
            id: "id",
            accessor: "id",
            disableSortBy: true,
            Cell: (arg: any) => {
                return (
                    <ChangeMenu
                        change={arg.row.original}
                        onDelete={changeId =>
                            alertingApi.deleteChange(changeId).then(
                                _ => setChanges(changes.filter(c => c.id !== changeId)),
                                error =>
                                    alerting.dispatchError(error,"CHANGE_DELETE", "Failed to delete change " + changeId)
                            )
                        }
                        onUpdate={change =>
                            alertingApi.updateChange(change.id, change).then(
                                _ => setChanges(changes.map(c => (c.id === change.id ? change : c))),
                                error =>
                                    alerting.dispatchError(error,"CHANGE_UPDATE", "Failed to update change " + change.id)
                            )
                        }
                    />
                )
            },
        })
    }
    // TODO: this doesn't work, table won't get updated when selected changes
    const selected = { [changes.findIndex(c => c.id === selectedChangeId)]: true }
    return <CustomTable<Change> columns={columns} data={changes} selected={selected} cellModifier="wrap" />
}

type ChangesTabsProps = {
    variables: Variable[]
    fingerprint: FingerprintValue | undefined
    testOwner?: string
    selectedChangeId?: number
    selectedVariableId?: number
}

export const ChangesTabs = ({
    variables,
    fingerprint,
    testOwner,
    selectedChangeId,
    selectedVariableId,
}: ChangesTabsProps) => {
    const [isExpanded, setExpanded] = useState(false)
    const [activeTab, setActiveTab] = useState<number | string>(0)
    useEffect(() => {
        const index = variables.findIndex(v => v.id === selectedVariableId)
        if (index >= 0) {
            setExpanded(true)
            setActiveTab(index)
        }
    }, [selectedVariableId, variables])
    const name = variables[0].group || variables[0].name
    return (
        <ExpandableSection
            toggleText={isExpanded ? "Hide changes in " + name : "Show changes in " + name}
            onToggle={(_event, val) => setExpanded(val)}
            isExpanded={isExpanded}
        >
            <Tabs
                activeKey={activeTab}
                onSelect={(e, index) => {
                    setActiveTab(index)
                }}
            >
                {variables.map((v, index) => (
                    <Tab key={v.name} eventKey={index} title={v.name}>
                        <ChangeTable
                            varId={v.id}
                            fingerprint={fingerprint}
                            testOwner={testOwner}
                            selectedChangeId={selectedChangeId}
                        />
                    </Tab>
                ))}
            </Tabs>
        </ExpandableSection>
    )
}
