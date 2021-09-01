import React, { Component } from "react";
import { Form, Alerts } from "../inputs";
import * as IzanamiServices from "../services/index";
import moment from "moment";
import Cookies from "js-cookie";

export class LoginPage extends Component {
  formSchema = {
    userId: { type: "stringAnimated", props: { label: "Login", placeholder: "Login" } },
    password: {
      type: "stringAnimated",
      props: { label: "Password", placeholder: "Password", type: "password" }
    }
  };

  formFlow = ["userId", "password"];

  state = {
    value: {
      userId: "",
      password: ""
    },
    redirectToReferrer: false
  };

  clean = () => {
    this.setState({
      value: {
        userId: "",
        password: ""
      },
      error: false
    });
  };

  componentDidMount() {
    window.addEventListener("keydown", this.loginOnKeyDown);
  }

  componentWillUnmount() {
    window.removeEventListener("keydown", this.loginOnKeyDown);
  }

  login = () => {
    IzanamiServices.fetchLogin(this.state.value).then(({ status, body }) => {
      if (status === 200) {
        if (body.changeme) {
          const d = moment().add(1, "minute");
          Cookies.set("notifyuser", "true", { path: "/", expires: d.toDate() });
        }
        this.setState({ error: false });
        window.location.href =
          window.__contextPath === "" ? "/" : window.__contextPath;
      } else {
        this.setState({ error: true });
      }
    });
  };

  loginOnKeyDown = event => {
    if (event.key === "Enter") {
      this.login();
    }
  };

  render() {
    return (
      <div className="container-fluid">
        <div className="row">
          <div className="text-center col-12">
            <img
              className="logo_izanami_dashboard"
              src={`${window.__contextPath}/assets/images/izanami.png`}
            />
          </div>
          <div className="col-md-4 offset-md-4 mt-4" >
            <Form
              value={this.state.value}
              onChange={value => this.setState({ value })}
              flow={this.formFlow}
              schema={this.formSchema}
            >
              <hr />
              {this.state.error && (
                <div className="offset-sm-2 panel-group">
                  <Alerts
                    display={this.state.error}
                    messages={[{ message: "auth.invalid.login" }]}
                  />
                </div>
              )}
              <div className="d-flex justify-content-end">
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={this.clean}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={this.login}
                >
                  <i className="fas fa-hdd"></i> Login
                </button>
              </div>
            </Form>
          </div>
        </div>
      </div>
    );
  }
}
