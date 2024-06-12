import React, { ReactElement } from "react";
export function Modal(props: {
  visible: boolean;
  onClose: () => void;
  onConfirm?: () => void;
  closeButtonText?: string;
  confirmButtonText?: string;
  children: ReactElement | ReactElement[] | string;
}) {
  const { visible, onClose, children, closeButtonText } = props;

  React.useEffect(() => {
    const close = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
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
          className={`modal-dialog modal-dialog-start modal-lg ${
            visible ? "anim__upToBottom" : ""
          }`}
        >
          <div className="modal-content">
            <div className="modal-body">{children}</div>
            <div className="modal-footer">
              <button
                type="button"
                className="btn btn-secondary"
                data-bs-dismiss="modal"
                onClick={() => onClose()}
              >
                {closeButtonText ? closeButtonText : "Esc"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
