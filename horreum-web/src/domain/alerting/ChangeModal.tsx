import {Change} from "../../generated";
import {useEffect, useState} from "react";
import {
    ActionGroup, Button,
    Form,
    FormGroup,
    Modal,
    Switch, TextArea
} from "@patternfly/react-core";

type ChangeModalProps = {
    change?: Change
    isOpen: boolean
    onClose(): void
    onUpdate(change: Change): void
}

export const ChangeModal = ({ change, isOpen, onClose, onUpdate }: ChangeModalProps) => {
    const [description, setDescription] = useState(change?.description)
    const [confirmed, setConfirmed] = useState(change?.confirmed)
    useEffect(() => {
        setDescription(change?.description)
        setConfirmed(change?.confirmed)
    }, [change])
    return (
        <Modal title={change?.confirmed ? "Confirm change" : "Edit change"} isOpen={isOpen} onClose={onClose}>
            <Form>
                <FormGroup label="Confirmed" fieldId="confirmed">
                    <Switch
                        id="confirmed"
                        isChecked={confirmed}
                        onChange={(_event, val) => setConfirmed(val)}
                        label="Confirmed"
                        labelOff="Not confirmed"
                    />
                </FormGroup>
                <FormGroup label="Description" fieldId="description">
                    <TextArea
                        value={description || ""}
                        type="text"
                        id="description"
                        aria-describedby="description-helper"
                        name="description"
                        onChange={(_event, val) => setDescription(val)}
                    />
                </FormGroup>
            </Form>
            <ActionGroup>
                <Button
                    variant="primary"
                    onClick={() => {
                        if (change) {
                            onUpdate({ ...change, description: description || "", confirmed: !!confirmed })
                        }
                        onClose()
                    }}
                >
                    Save
                </Button>
                <Button variant="secondary" onClick={onClose}>
                    Cancel
                </Button>
            </ActionGroup>
        </Modal>
    )
}