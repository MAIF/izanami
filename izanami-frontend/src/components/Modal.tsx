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
  } = props;

  React.useEffect(() => {
    const close = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        props.onClose();
      }
    };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, []);

  return (
    <>
      <div
        className="modal"
        tabIndex={-1}
        aria-hidden={visible ? "false" : "true"}
        style={visible ? { display: "block", backgroundColor: "#0008" } : {}}
      >
        <div
          className={`modal-dialog ${
            position === "top" ? "modal-dialog-start" : "modal-dialog-centered"
          } modal-lg ${visible ? "anim__upToBottom" : ""}`}
        >
          <div className="modal-content">
            {title && (
              <div className="modal-header">
                <h5 className="modal-title">{title}</h5>
              </div>
            )}
            <div className="modal-body">{children}</div>
            <div className="modal-footer">
              <button
                type="button"
                className={`btn ${
                  !closeButtonText && !onConfirm
                    ? "btn-danger"
                    : "btn-danger-light"
                }`}
                data-bs-dismiss="modal"
                onClick={() => onClose()}
              >
                {closeButtonText
                  ? closeButtonText
                  : onConfirm
                  ? "Cancel"
                  : "Close"}
              </button>
              {onConfirm && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={onConfirm}
                >
                  {confirmButtonText ? confirmButtonText : "Confirm"}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
