import React, { ReactElement } from "react";

export function Modal(props: {
  title?: string;
  visible: boolean;
  onClose: () => void;
  onConfirm?: () => void;
  closeButtonText?: string;
  confirmButtonText?: string;
  children: ReactElement | ReactElement[] | string;
  position?: "top" | "center";
  style?: React.CSSProperties;
}) {
  const {
    visible,
    onClose,
    children,
    onConfirm,
    title,
    closeButtonText,
    confirmButtonText,
    position,
    style,
  } = props;

  React.useEffect(() => {
    const close = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        props.onClose();
      }
    };
    window.addEventListener("keydown", close);
    return () => {
      window.removeEventListener("keydown", close);
    };
  }, []);

  return (
    <RawModal visible={visible} position={position}>
      <>
        {title && (
          <div className="modal-header">
            <h5 className="modal-title">{title}</h5>
          </div>
        )}
        <div className="modal-body" style={style}>
          {children}
        </div>

        <div className="modal-footer">
          <button
            type="button"
            className={`btn ${
              !closeButtonText && !onConfirm ? "btn-danger" : "btn-danger-light"
            }`}
            data-bs-dismiss="modal"
            onClick={() => onClose()}
          >
            {closeButtonText ? closeButtonText : onConfirm ? "Cancel" : "Close"}
          </button>
          {onConfirm && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={onConfirm}
              aria-label="Confirm"
            >
              {confirmButtonText ? confirmButtonText : "Confirm"}
            </button>
          )}
        </div>
      </>
    </RawModal>
  );
}

export function RawModal(props: {
  visible: boolean;
  children: ReactElement | ReactElement[] | string;
  position?: "top" | "center";
}) {
  const { visible, position, children } = props;

  return (
    <>
      <div
        id="modal"
        className="modal"
        tabIndex={-1}
        aria-hidden={visible ? "false" : "true"}
        style={{
          display: visible ? "block" : "none",
          backgroundColor: "#0008",
          width: "100%",
        }}
      >
        <div
          className={`modal-dialog ${
            position === "top" ? "modal-dialog-start" : "modal-dialog-centered"
          } modal-lg ${visible ? "anim__upToBottom" : ""}`}
        >
          <div className="modal-content">{children}</div>
        </div>
      </div>
    </>
  );
}
