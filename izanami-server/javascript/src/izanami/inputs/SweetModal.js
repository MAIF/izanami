import React, {Component, PureComponent} from "react";
import { Modal } from 'bootstrap';

export class SweetModal extends PureComponent {

  state = {
    confirmToDelete: "",
    errors: []
  }

  componentDidUpdate() {
    if (this.state.confirmToDelete !== "" || this.state.errors.length != 0) {
      this.setState({
        confirmToDelete: "",
        errors: []
      });
    }
    this.toggleModal(this.props.open);
  }

  toggleModal = (open = false) => {
    let myModal = new Modal(document.getElementById(`${this.props.id}`));
    if (open) {
      console.log(`show ${this.props.id}`)
      myModal.show();
    } else {
      console.log(`hide ${this.props.id}`)
      myModal.hide();
    }
  };

  componentWillUnmount() {
    let myModal = new Modal(document.getElementById(`${this.props.id}`));
    myModal.hide()
  }

  handleInputChange = event => {
    const target = event.target;
    const value = target.type === "checkbox" ? target.checked : target.value;
    const name = target.name;

    const nextState = Object.assign({}, this.state, {[name]: value});
    this.setState(nextState);
  };

  confirm = e => {
    if (this.props.confirmToDelete) {
      if (this.state.confirmToDelete === this.props.confirmToDelete) {
        this.props.confirm(e);
      } else {
        const errors = [];
        errors.push("confirmToDelete.error");
        this.setState({errors});
      }
    } else {
      this.props.confirm(e);
    }
  };

  onDismiss = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (this.props.onDismiss) {
      this.props.onDismiss(e);
    }
  };

  render() {
    if (this.props.type === "success" || this.props.type === "error") {
      return (
        <div
          className="modal fade"
          tabIndex="-1"
          role="dialog"
          id={this.props.id}>
          <div className="modal-dialog" role="document">
            <div className="modal-content">
              <div className="modal-header">
                <h4 className="modal-title text-center">{this.props.title}</h4>
              </div>
              <div className="modal-body text-center">
                {this.props.children}
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-success"
                  data-bs-dismiss="modal">
                  Ok
                </button>
              </div>
            </div>
          </div>
        </div>
      );
    }

    if (this.props.type === "confirm") {
      return (
        <div
          className="modal fade"
          tabIndex="-1"
          role="dialog"
          id={this.props.id}>
          <div className="modal-dialog" role="document">
            <div className="modal-content">
              <div className="modal-header">
                <h4 className="modal-title text-center">{this.props.title}</h4>
              </div>
              <div className="modal-body text-center">
                {this.props.children}

                {this.props.confirmToDelete && (
                  <div
                    className={
                      this.state.errors.indexOf("confirmToDelete.error") !== -1
                        ? "row mb-3 has-error"
                        : "row mb-3"
                    }>
                    <label
                      htmlFor="serviceName"
                      className="col-xs-12 col-sm-2 col-form-label">
                      {this.props.labelRemove || "App name to delete"}
                    </label>
                    <div className="col-sm-10 input-group">
                      <input
                        type="text"
                        className="form-control"
                        id="serviceName"
                        placeholder="name"
                        name="confirmToDelete"
                        value={this.state.confirmToDelete}
                        onChange={this.handleInputChange}
                      />
                    </div>
                  </div>
                )}
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-danger"
                  data-bs-dismiss="modal"
                  onClick={this.onDismiss}>
                  Cancel
                </button>

                <button
                  type="button"
                  className="btn btn-success"
                  data-bs-dismiss="modal"
                  onClick={this.confirm}>
                  {this.props.labelValid || "Confirm"}
                </button>
              </div>
            </div>
          </div>
        </div>
      );
    }

    return (
      <div
        className="modal fade"
        tabIndex="-1"
        role="dialog"
        id={this.props.id}>
        <div className="modal-dialog" role="document">
          <div className="modal-content">
            <div className="modal-header">
              <h4 className="modal-title text-center">{this.props.title}</h4>
            </div>
            <div className="modal-body text-center">{this.props.children}</div>
            {this.props.allowClosed && (
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-success"
                  data-bs-dismiss="modal">
                  Ok
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
}
