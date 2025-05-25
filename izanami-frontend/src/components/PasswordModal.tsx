import React, { ReactElement, useState } from "react";
import { useForm, FormProvider } from "react-hook-form";
import { Modal } from "./Modal";
import { PASSWORD_REGEXP } from "../utils/patterns";

export function InputConfirmationModal(props: {
  title?: string;
  isOpenModal: boolean;
  onClose: () => void;
  onConfirm: () => void;
  children: ReactElement | ReactElement[] | string;
  expectedValue: string;
}) {
  const { isOpenModal, title, onClose, onConfirm, children, expectedValue } =
    props;
  const [input, setInput] = useState<string>("");

  if (!isOpenModal) return null;

  return (
    <Modal
      title={title}
      visible={isOpenModal}
      onConfirm={() => {
        if (input === expectedValue) {
          onConfirm();
          setInput("");
        }
      }}
      onClose={() => {
        onClose();
        setInput("");
      }}
    >
      <>{children}</>
      <div>
        <form
          autoComplete="off"
          onSubmit={(e) => {
            e.preventDefault();
            const input = e?.target?.confirmation.value;
            if (input === expectedValue) {
              onConfirm();
              setInput("");
            }
          }}
        >
          <input
            type="text"
            name="confirmation"
            className="form-control"
            aria-label="Confirmation"
            aria-required="true"
            autoComplete="off"
            onChange={(e) => {
              const newValue = e?.target?.value;
              setInput(newValue);
            }}
            value={input}
          />
        </form>
      </div>
    </Modal>
  );
}
