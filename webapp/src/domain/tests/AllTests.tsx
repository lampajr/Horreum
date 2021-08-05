import React, { useState, useMemo, useEffect } from 'react';

import { useSelector } from 'react-redux'
import { useDispatch } from 'react-redux'
import {
    Card,
    CardHeader,
    CardBody,
    Dropdown,
    DropdownToggle,
    DropdownItem,
    PageSection,
    Spinner,
} from '@patternfly/react-core';
import { NavLink } from 'react-router-dom';
import {
    EyeIcon,
    EyeSlashIcon,
    FolderOpenIcon,
} from '@patternfly/react-icons'

import {
   fetchSummary,
   updateAccess,
   deleteTest,
   allSubscriptions,
   addUserOrTeam,
   removeUserOrTeam,
} from './actions';
import * as selectors from './selectors';

import Table from '../../components/Table';
import AccessIcon from '../../components/AccessIcon';
import ActionMenu, { MenuItem, ActionMenuProps, useChangeAccess } from '../../components/ActionMenu';
import OwnerSelect, { Role, SHOW_ALL, ONLY_MY_OWN } from '../../components/OwnerSelect'
import ConfirmTestDeleteModal from './ConfirmTestDeleteModal'

import {
  isAuthenticatedSelector,
  useTester,
  roleToName,
  rolesSelector,
  userProfileSelector,
} from '../../auth'
import { alertAction } from '../../alerts'
import { CellProps, Column, UseSortByColumnOptions } from 'react-table';
import { Test, TestDispatch } from './reducers';
import { Access } from '../../auth'

type WatchDropdownProps = {
  id: number,
  watching?: string[],
}

const WatchDropdown = ({ id, watching } : WatchDropdownProps) => {
  const [open, setOpen] = useState(false)
  const roles = useSelector(rolesSelector)
  const profile = useSelector(userProfileSelector)
  const dispatch = useDispatch();
  if (watching === undefined) {
    return <Spinner size="sm" />
  }
  const personalItems = []
  const self = profile?.username || "__self"
  const isOptOut = watching.some(u => u.startsWith("!"))
  if (watching.some(u => u === profile?.username)) {
    personalItems.push(<DropdownItem
        key="__self"
        onClick={ () => dispatch(removeUserOrTeam(id, self)) }
      >Stop watching personally</DropdownItem>)
  } else {
    personalItems.push(<DropdownItem
        key="__self"
        onClick={ () => dispatch(addUserOrTeam(id, self)) }
      >Watch personally</DropdownItem>)
  }
  if (isOptOut) {
    personalItems.push(<DropdownItem
        key="__optout"
        onClick={ () => dispatch(removeUserOrTeam(id, "!" + self)) }
    >Resume watching per team settings</DropdownItem>)
  } else if (watching.some(u => u.endsWith("-team"))) {
    personalItems.push(<DropdownItem
        key="__optout"
        onClick={ () => dispatch(addUserOrTeam(id, "!" + self)) }
    >Opt-out of all notifications</DropdownItem>)
  }
  return (
    <Dropdown
      isOpen={open}
      isPlain
      onSelect={_ => setOpen(false) }
      toggle={
        <DropdownToggle toggleIndicator={null} onToggle={setOpen}>
          { !isOptOut && <EyeIcon className="watchIcon" style={{ cursor: "pointer", color: (watching.length > 0 ? "#151515" : "#d2d2d2" )}}/> }
          { isOptOut && <EyeSlashIcon className="watchIcon" style={{ cursor: "pointer", color: "#151515"}} /> }
        </DropdownToggle>
      }>
        { personalItems }
        {
          roles.filter(role => role.endsWith("-team"))
               .sort()
               .map(role => watching.some(u => u === role) ? (
                <DropdownItem key={role}
                  onClick={ () => dispatch(removeUserOrTeam(id, role)) }
                >Stop watching as team { roleToName(role) }</DropdownItem>
                ) : (
                  <DropdownItem key={role}
                    onClick={ () => dispatch(addUserOrTeam(id, role)) }
                  >Watch as team { roleToName(role) }</DropdownItem>
                ))
        }

    </Dropdown>
  )
}

type C = CellProps<Test>
type Col = Column<Test> & UseSortByColumnOptions<Test>

type DeleteConfig = {
  name: string,
}

function useDelete(config: DeleteConfig): MenuItem<DeleteConfig> {
  const [confirmDeleteModalOpen, setConfirmDeleteModalOpen] = useState(false)
  const dispatch = useDispatch();
  const thunkDispatch = useDispatch<TestDispatch>()
  return [ (props: ActionMenuProps, isOwner: boolean, close: () => void, config: DeleteConfig) => {
    return {
      item:
        <DropdownItem
            key="delete"
            onClick={() => {
              close()
              setConfirmDeleteModalOpen(true)
            }}
            isDisabled={!isOwner}
        >
            Delete
        </DropdownItem>,
      modal:
        <ConfirmTestDeleteModal
            key="delete"
            isOpen={ confirmDeleteModalOpen }
            onClose={ () => setConfirmDeleteModalOpen(false) }
            onDelete={ () => {
              thunkDispatch(deleteTest(props.id)).catch(e => {
                dispatch(alertAction("DELETE_TEST", "Failed to delete test", e))
              })
            }}
            testId={ props.id }
            testName={ config.name }
        />
    }
  }, config]
}

export default function AllTests() {
    document.title = "Tests | Horreum"
    const dispatch = useDispatch();
    const thunkDispatch = useDispatch<TestDispatch>()
    const watchingColumn: Col = {
      Header:"Watching",
      accessor: "watching",
      disableSortBy: true,
      Cell: (arg: C) => {
        return (<WatchDropdown watching={ arg.cell.value } id={arg.row.original.id} />)
      }
    }
    let columns: Col[] = useMemo(()=>[
        {
          Header:"Id",accessor:"id",
          Cell: (arg: C) => {
            const {cell: {value} } = arg;
            return (<NavLink to={`/test/${value}`}>{value}</NavLink>)
          }
        },
        {
          Header: "Access", accessor:"access",
          Cell: (arg: C) => <AccessIcon access={arg.cell.value} />
        },
        {Header:"Owner",accessor:"owner", Cell: (arg: C) => roleToName(arg.cell.value)},
        {Header:"Name",accessor:"name", Cell: (arg: C) => (<NavLink to={`/test/${arg.row.original.id}`}>{ arg.cell.value }</NavLink>)},
        {Header:"Description",accessor:"description"},
        {
          Header:"Run Count",accessor:"count",
          Cell: (arg: C) => {
            const {cell: {value, row: {index}}, data} = arg;
            return (<NavLink to={`/run/list/${data[index].id}`}>{value}&nbsp;<FolderOpenIcon /></NavLink>)
          }
        },
        {
          Header:"Actions",
          id:"actions",
          accessor: "id",
          Cell: (arg: C) => {
            const changeAccess = useChangeAccess({
              onAccessUpdate: (id: number, owner: string, access: Access) => {
                thunkDispatch(updateAccess(id, owner, access)).catch(e => {
                  dispatch(alertAction("UPDATE_TEST", "Failed to update test", e))
                })
              }
            })
            const del = useDelete({
              name: arg.row.original.name
            })
            return (
            <ActionMenu
              id={arg.cell.value}
              access={arg.row.original.access}
              owner={arg.row.original.owner}
              description={ "test " + arg.row.original.name }
              items={[ changeAccess, del ]}
            />
            )
          }
        }
    ], [dispatch, thunkDispatch])
    const allTests = useSelector(selectors.all);
    const roles = useSelector(rolesSelector)
    const isAuthenticated = useSelector(isAuthenticatedSelector)
    const [rolesFilter, setRolesFilter] = useState<Role>(ONLY_MY_OWN)
    useEffect(()=>{
        dispatch(fetchSummary(rolesFilter.key))
    },[dispatch, roles, rolesFilter])
    useEffect(() => {
      if (isAuthenticated) {
        dispatch(allSubscriptions())
      }
    }, [dispatch, isAuthenticated, rolesFilter])
    if (isAuthenticated) {
      columns = [ watchingColumn, ...columns ]
    }

    const isTester = useTester()
    const isLoading = useSelector(selectors.isLoading)
    return (
        <PageSection>
          <Card>
            <CardHeader>
              { isTester &&
              <NavLink className="pf-c-button pf-m-primary" to="/test/_new">
                New Test
              </NavLink>
              }
              { isAuthenticated && <div style={{ width: "200px", marginLeft: "16px" }}>
                <OwnerSelect
                  includeGeneral={true}
                  selection={rolesFilter}
                  onSelect={selection => {
                    setRolesFilter(selection)
                  }} />
              </div> }
            </CardHeader>
            <CardBody>
              <Table columns={columns} data={allTests || []} isLoading={isLoading}/>
            </CardBody>
          </Card>
        </PageSection>
    )
}
