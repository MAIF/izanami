import toast from "react-hot-toast";

export function successToast(message: string, id?: string) {
  toast(
    <div
      style={{
        display: "flex",
        alignItems: "center",
      }}
    >
      {message}
      <button onClick={() => toast.dismiss(id ?? message)} className="btn btn-sm ms-3 me-0">
        X
      </button>
    </div>,
    {
      id: id ?? message,
      icon: <i className="fa-solid fa-check" aria-hidden></i>,
      className: "toast-success",
    },
  );
}

export function errorToast(message: string, id?: string) {
  toast.error(
    <div
      style={{
        display: "flex",
        alignItems: "center",
      }}
    >
      {message}
      <button onClick={() => toast.dismiss(id ?? message)} className="btn btn-sm ms-3 me-0">
        X
      </button>
    </div>,
    {
      id: id ?? message,
      icon: <i className="fa-solid fa-circle-exclamation" aria-hidden></i>,
      className: "toast-error",
    },
  );
}
