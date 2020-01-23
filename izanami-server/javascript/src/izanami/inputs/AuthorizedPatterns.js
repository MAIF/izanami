import React, { Component } from "react";

class Right extends Component {
    active = () => {
        if (this.props.selected) {
            return "active"
        } {
            return "";
        }
    };

    render() {
        console.log('Letter', this.props.letter, 'selected', this.props.selected);
        return (
            <button type="button"
                    className={`btn btn-success ${this.active()}`}
                    onClick={() => this.props.onChange(!this.props.selected)}>
                {this.props.letter}
            </button>
        )
    }
}

class AuthorizedPattern extends Component {

    changeKey = (e) => {
        if (e && e.preventDefault) e.preventDefault();
        this.props.onChange({
            pattern: e.target.value,
            rights: this.props.value.rights
        })
    };

    set = (letter, active) => {
        const removed = this.props.value.rights.filter(l => l !== letter);
        const rights = active ? [...removed, letter] : [...removed];
        this.props.onChange({
            pattern: this.props.value.pattern,
            rights
        })
    };

    render() {
        return (<div>
            <input
                disabled={this.props.disabled}
                type="text"
                className="form-control"
                style={{ width: "50%" }}
                placeholder={"*"}
                value={this.props.value.pattern}
                onChange={e => this.changeKey(e)}
            />
            <div className="btn-group" role="group" >
                <Right letter={"C"} selected={this.props.value.rights.includes("C")} onChange={v => this.set("C", v)}/>
                <Right letter={"R"} selected={this.props.value.rights.includes("R")} onChange={v => this.set("R", v)}/>
                <Right letter={"U"} selected={this.props.value.rights.includes("U")} onChange={v => this.set("U", v)}/>
                <Right letter={"D"} selected={this.props.value.rights.includes("D")} onChange={v => this.set("D", v)}/>
            </div>
        </div>);
    }
}


export class AuthorizedPatterns extends Component {
    state = {
        values: []
    };

    componentDidMount() {
        this.setState({ values: this.props.value });
    }

    componentWillReceiveProps(nextProps) {
        this.setState({ values: nextProps.value });
    }

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
    };

    render() {
        return (
            <div>
                {this.state.values.length === 0 && (
                    <div className="form-group">
                        <label
                            htmlFor={`input-${this.props.label}`}
                            className="col-sm-2 control-label"
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
                                <i className="glyphicon glyphicon-plus-sign" />{" "}
                            </button>
                        </div>
                    </div>
                )}
                {this.state.values.map((value, idx) => (
                    <div className="form-group" key={`obj-${idx}`}>
                        {idx === 0 && (
                            <label className="col-sm-2 control-label">
                                {this.props.label}
                            </label>
                        )}
                        {idx > 0 && (
                            <label className="col-sm-2 control-label">&nbsp;</label>
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
                    <i className="glyphicon glyphicon-trash" />
                  </button>
                                    {idx === this.state.values.length - 1 && (
                                        <button
                                            disabled={this.props.disabled}
                                            type="button"
                                            className="btn btn-primary"
                                            onClick={this.addNext}
                                        >
                                            <i className="glyphicon glyphicon-plus-sign" />{" "}
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
