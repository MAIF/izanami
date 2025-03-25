import * as React from "react";
import { useContext, useState } from "react";
import { useNavigate, NavLink } from "react-router-dom";
import { IzanamiContext, MODE_KEY, Modes } from "../securityContext";
import Logo from "../../izanami.png";
import { TUser } from "../utils/types";
import { Loader } from "../components/Loader";

export function Login(props: any) {
  const code = new URLSearchParams(props.location.search).get("code");
  if (code) {
    return <TokenWaitScreen code={code} />;
  } else {
    return <LoginForm {...props} />;
  }
}

function TokenWaitScreen({ code }: { code: string }) {
  const fetching = React.useRef<boolean>(false);
  const [error, setError] = React.useState("");
  React.useEffect(() => {
    if (!fetching.current) {
      fetching.current = true;
      setError("");
      fetch("/api/admin/openid-connect-callback", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({ code }),
      }).then((response) => {
        if (response.status >= 400) {
          setError("Failed to retrieve token from code");
        } else {
          window.location.href = "/";
        }
      });
    }
  }, []);

  if (error) {
    return <div>{error}</div>;
  } else if (fetching) {
    return (
      <div>
        <Loader message="Waiting redirection..." />
      </div>
    );
  } else {
    return <div>Fetched !!!</div>;
  }
}

function LoginForm(props: { req?: string }) {
  const navigate = useNavigate();
  const req = props.req;
  const { setUser, integrations, expositionUrl } = useContext(IzanamiContext);
  const [error, setError] = useState<string>("");
  const [loading, setLoading] = useState<boolean>(false);
  const [lightMode, setLightMode] = useState<boolean>(false);

  React.useEffect(() => {
    let mode = window.localStorage.getItem(MODE_KEY);
    setLightMode(mode !== Modes.dark);
    if (mode === Modes.light) {
      document.documentElement.setAttribute("data-theme", Modes.light);
    }
  }, []);

  const handleCheckboxChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setLightMode(event.target.checked);
    if (lightMode) {
      document.documentElement.setAttribute("data-theme", "dark");
      window.localStorage.setItem(MODE_KEY, "dark");
    } else {
      document.documentElement.setAttribute("data-theme", Modes.light);
      window.localStorage.setItem(MODE_KEY, Modes.light);
    }
  };

  return (
    <div className="d-flex flex-column justify-content-center align-items-center">
      <img
        src={Logo}
        style={{
          marginBottom: 48,
          height: 300,
        }}
      />
      <form
        className="d-flex flex-column container-fluid"
        style={{ maxWidth: "400px" }}
        onSubmit={(e) => {
          e.preventDefault();
          if (error) {
            setError("");
          }
          const data = new FormData(e.target as HTMLFormElement);
          const login = data.get("login");
          const password = data.get("password");
          if (!login || !password) {
            setError("Both login and password must be specified");
          } else {
            setLoading(true);
            e.preventDefault();
            fetch("/api/admin/login?rights=true", {
              method: "POST",
              headers: {
                Authorization: `Basic ${btoa(`${login}:${password}`)}`,
              },
            })
              .then((res) => {
                setLoading(false);
                if (res.status >= 400) {
                  return res.text().then((err) => {
                    throw err;
                  });
                }
                return res.json();
              })
              .then((user: TUser) => {
                setUser(user);
                navigate(req ? req : "/");
              })
              .catch((err) => {
                setLoading(false);
                let error = "An error occured";
                try {
                  const json = JSON.parse(err);
                  if (json?.message) {
                    error += ` : ${json.message}`;
                  }
                } catch (err) {
                  //
                }
                setError(error);
              });
          }
        }}
      >
        <label className="form-label">
          Username{" "}
          <input
            type="text"
            name="login"
            className="form-control"
            onChange={() => {
              if (error) {
                setError("");
              }
            }}
          />
        </label>
        <label className="form-label">
          Password{" "}
          <input
            name="password"
            type="password"
            className="form-control"
            onChange={() => {
              if (error) {
                setError("");
              }
            }}
          />
        </label>
        <NavLink className={() => "align-self-end"} to={"/forgotten-password"}>
          Forgot password ?
        </NavLink>
        {loading ? (
          <div>Login...</div>
        ) : (
          <>
            <button value="Login" className="btn btn-primary">
              Login
            </button>
            {error && (
              <div className="error-message" style={{ width: "100%" }}>
                {error}
              </div>
            )}

            {integrations?.oidc && (
              <>
                <div className="openid-separator my-3">OR</div>

                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => {
                    window.location.href = `${expositionUrl}/api/admin/openid-connect`;
                  }}
                >
                  OpenId connect
                </button>
              </>
            )}
            <div className="d-flex align-items-center justify-content-center mt-3">
              <i className="fa fa-moon" />
              <div className="form-check form-switch mx-2">
                <input
                  className="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="flexSwitchCheckChecked"
                  checked={lightMode}
                  onChange={handleCheckboxChange}
                />
                <label
                  className="form-check-label"
                  htmlFor="flexSwitchCheckChecked"
                ></label>
              </div>
              <i className="fa fa-lightbulb" />
            </div>
          </>
        )}
      </form>
    </div>
  );
}
