import React, {Component, PureComponent} from "react";
import {KeyInput} from "./KeyInput";
import * as IzanamiServices from "../services";
import PropTypes from "prop-types";

class Right extends Component {

    static defaultProps = {
        disabled: false
    };

    active = () => {
        if (this.props.selected) {
            return "active"
        } {
            return "";
        }
    };

    render() {
        return (
            <button type="button"
                    disabled={this.props.disabled}
                    className={`btn btn-success ${this.active()}`}
                    onClick={() => this.props.onChange(!this.props.selected)}>
                {this.props.letter}
            </button>
        )
    }
}

class AuthorizedPattern extends Component {

    changeKey = (pattern) => {
        this.props.onChange({pattern, rights: this.props.value.rights})
    };

    set = (letter, active) => {
        const removed = this.props.value.rights.filter(l => l !== letter);
        const rights = active ? [...removed, letter] : [...removed];
        this.props.onChange({
            pattern: this.props.value.pattern,
            rights
        })
    };

    searchKey = query => {
        return IzanamiServices.search({query})
            .then((results) => results.map(({ id }) => id));
    };

    render() {
        return (
            <>
                <KeyInput
                    value={this.props.value.pattern}
                    search={this.searchKey}
                    onChange={e => this.changeKey(e)}
                />
                <div className="input-group-btn crud ps-sm-2 crud" role="group">
                    <Right letter={"C"} selected={this.props.value.rights.includes("C")} onChange={v => this.set("C", v)}/>
                    <Right letter={"R"} selected={true} disabled={true} />
                    <Right letter={"U"} selected={this.props.value.rights.includes("U")} onChange={v => this.set("U", v)}/>
                    <Right letter={"D"} selected={this.props.value.rights.includes("D")} onChange={v => this.set("D", v)}/>
                </div>
            </>
        );
    }
}


export class AuthorizedPatternsInput extends PureComponent {

    static propTypes = {
        value: PropTypes.array.isRequired
    };

    state = {
        values:  this.props.value || []
    };

    changeValue = (value, index) => {
        const values = this.state.values.map((v, i) => {
            if (i === index) {
                return value;
            } else {
                return v;
            }
        });

        this.setState({ values });
        this.props.onChange(values);
    };

    addFirst = e => {
        if (e && e.preventDefault) e.preventDefault();
        if (!this.props.value || Object.keys(this.props.value).length === 0) {
            const values = [
                this.props.defaultValue || {"pattern": "*", "rights": ["C", "R", "U", "D"]},
                ...this.state.values
            ];
            this.setState({ values });
        }
    };

    addNext = e => {
        if (e && e.preventDefault) e.preventDefault();
        const values = [...this.state.values, this.props.defaultValue || {"pattern": "*", "rights": ["C", "R", "U", "D"]}];
        this.setState({ values });
    };

    remove = (e, idx) => {
        if (e && e.preventDefault) e.preventDefault();
        const values = this.state.values.filter((_, i) => i !== idx);
        this.setState({ values });
        this.props.onChange(values);
    };

    render() {
        return (
            <div>
                {this.state.values.length === 0 && (
                    <div className="row mb-3">
                        <label
                            htmlFor={`input-${this.props.label}`}
                            className="col-sm-2 col-form-label"
                        >
                            {this.props.label}
                        </label>
                        <div className="col-sm-10">
                            <button
                                disabled={this.props.disabled}
                                type="button"
                                className="btn btn-primary"
                                onClick={this.addFirst}
                            >
                                <i className="fas fa-plus-circle"></i>{" "}
                            </button>
                        </div>
                    </div>
                )}
                {this.state.values && this.state.values.map((value, idx) => (
                    <div className="row mb-3" key={`obj-${idx}`}>
                        {idx === 0 && (
                            <label className="col-sm-2 col-form-label">
                                {this.props.label}
                            </label>
                        )}
                        {idx > 0 && (
                            <label className="col-sm-2 col-form-label">&nbsp;</label>
                        )}
                        <div className="col-sm-10">
                            <div className="input-group">
                                <AuthorizedPattern value={value}
                                                   onChange={ v => this.changeValue(v, idx)}
                                />
                                <span className="input-group-btn">
                                  <button
                                      disabled={this.props.disabled}
                                      type="button"
                                      className="btn btn-danger"
                                      onClick={e => this.remove(e, idx)}
                                  >
                                    <i className="fas fa-trash-alt"></i>
                                  </button>
                                    {idx === this.state.values.length - 1 && (
                                        <button
                                            disabled={this.props.disabled}
                                            type="button"
                                            className="btn btn-primary"
                                            onClick={this.addNext}
                                        >
                                            <i className="fas fa-plus-circle"></i>{" "}
                                        </button>
                                    )}
                                </span>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        );
    }
}
