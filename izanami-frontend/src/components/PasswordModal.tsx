import React, { ReactElement, useState } from "react";
import { useForm, FormProvider, useFormContext } from "react-hook-form";
import { createRoot } from "react-dom/client";
import { Modal } from "./Modal";
import { PASSWORD_REGEXP } from "../utils/patterns";

interface PasswordModalForm {
  password: string;
}
const PasswordInput = () => {
  const {
    register,
    formState: { errors },
  } = useFormContext<PasswordModalForm>();

  return (
    <div>
      <label htmlFor="password-input">
        Please enter your password to confirm deletion:
      </label>
      <form autoComplete="off">
        <input
          id="password-input"
          type="password"
          {...register("password", {
            required: "Password name must be specified.",
            pattern: {
              value: PASSWORD_REGEXP,
              message: `Password name must match ${PASSWORD_REGEXP}.`,
            },
          })}
          className="form-control"
          aria-label="Password"
          aria-required="true"
          aria-invalid={!!errors.password}
          autoComplete="off"
        />
      </form>

      {errors.password && (
        <div id="password-error" className="error-message" role="alert">
          {errors.password.message}
        </div>
      )}
    </div>
  );
};

export function PasswordModal(props: {
  title?: string;
  isOpenModal: boolean;
  onClose: () => void;
  onConfirm: (password: string) => void;
  children: ReactElement | ReactElement[] | string;
}) {
  const { isOpenModal, title, onClose, onConfirm, children } = props;
  const methods = useForm();
  const onSubmit = (data: any) => {
    onConfirm(data.password);
    methods.reset();
  };

  if (!isOpenModal) return null;

  return (
    <FormProvider {...methods}>
      <Modal
        title={title}
        visible={isOpenModal}
        onConfirm={methods.handleSubmit(onSubmit)}
        onClose={() => {
          onClose();
          methods.reset();
        }}
      >
        <>{children}</>
        <PasswordInput />
      </Modal>
    </FormProvider>
  );
}

export function askPasswordConfirmation(
  message: string,
  onConfirm: (password: string) => Promise<void>,
  title?: string
): Promise<string> {
  return new Promise((resolve, reject) => {
    const ModalWrapper = () => {
      const [isOpen, setIsOpen] = useState(true);

      const handleConfirm = async (password: string) => {
        try {
          await onConfirm(password);
          setIsOpen(false);
          resolve(password);
        } catch (error) {
          reject(error);
        }
      };

      const handleClose = () => {
        setIsOpen(false);
      };

      return (
        <PasswordModal
          isOpenModal={isOpen}
          onClose={handleClose}
          onConfirm={handleConfirm}
          title={title}
        >
          {message}
        </PasswordModal>
      );
    };
    const modalContainer = document.createElement("div");
    document.body.appendChild(modalContainer);

    const root = createRoot(modalContainer);
    root.render(<ModalWrapper />);
  });
}
