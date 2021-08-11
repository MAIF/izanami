import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import { Link } from "react-router-dom";
import {
  Key,
  Table,
  CodeInput,
  ObjectInput,
  SimpleBooleanInput,
  AsyncSelectInput,
  TextInput,
  PercentageInput,
  ArrayInput
} from "../inputs";
import moment from "moment";
import {
  IzaDatePicker,
  IzaDateRangePicker
} from "../components/IzanamiDatePicker";
import {TimePicker} from "../components/TimePicker"
import * as ScriptsTemplate from "../helpers/ScriptTemplates";
import { JsLogo, KotlinLogo, ScalaLogo } from "../components/Logos";
import * as Abilitations from "../helpers/Abilitations";

const DATE_FORMAT = "DD/MM/YYYY HH:mm:ss";
const DATE_FORMAT2 = "YYYY-MM-DD HH:mm:ss";
const TIME_FORMAT = "HH:mm"

class FeatureParameters extends Component {
  componentDidUpdate(prevProps) {
    const currentStrategy = this.props.source.activationStrategy;
    const prevStrategy = prevProps.source.activationStrategy;
    if (currentStrategy !== prevStrategy) {
      if (prevStrategy === "RELEASE_DATE") {
        this.props.onChange({ releaseDate: undefined });
      } else if (prevStrategy === "SCRIPT") {
        this.props.onChange({ script: undefined, type: undefined });
      } else if (prevStrategy === "GLOBAL_SCRIPT") {
        this.props.onChange({ ref: undefined });
      } else if (currentStrategy === "PERCENTAGE") {
        this.props.onChange({ percentage: undefined });
      } else if (currentStrategy === "CUSTOMERS_LIST") {
        this.props.onChange({ customers: undefined });
      }
      if (currentStrategy === "SCRIPT") {
        this.props.onChange({
          type: "javascript",
          script: ScriptsTemplate.javascriptDefaultScript
        });
      } else if (currentStrategy === "RELEASE_DATE") {
        this.props.onChange({ releaseDate: moment().format(DATE_FORMAT) });
      } else if (currentStrategy === "HOUR_RANGE") {
        this.props.onChange({ startAt: moment().format(TIME_FORMAT), endAt: moment().add(1, "hour").format(TIME_FORMAT)});
      } else if (currentStrategy === "PERCENTAGE") {
        this.props.onChange({ percentage: 50 });
      } else if (currentStrategy === "NO_STRATEGY") {
        this.props.onChange({});
      } else if (currentStrategy === "CUSTOMERS_LIST") {
        this.props.onChange({});
      }
    }
  }

  render() {
    let content = <h1>No content ...</h1>;
    let label = "Parameters";
    if (this.props.source.activationStrategy === "NO_STRATEGY") {
      return (
        <ObjectInput
          label="Parameters"
          placeholderKey="Parameter name"
          placeholderValue="Parameter value"
          value={this.props.value}
          onChange={this.props.onChange}
        />
      );
    }
    if (this.props.source.activationStrategy === "RELEASE_DATE") {
      label = "Release date";
      const date = this.props.value.releaseDate
        ? moment(this.props.value.releaseDate, DATE_FORMAT)
        : moment();
      content = (
        <IzaDatePicker
          date={date}
          updateDate={d =>
            this.props.onChange({ releaseDate: d.format(DATE_FORMAT) })
          }
        />
      );
    }
    if (this.props.source.activationStrategy === "DATE_RANGE") {
      label = "Date range";
      const from = this.props.value.from
        ? moment(this.props.value.from, DATE_FORMAT2)
        : moment();
      const to = this.props.value.to
        ? moment(this.props.value.to, DATE_FORMAT2)
        : moment().add(1, "day");
      content = (
        <IzaDateRangePicker
          from={from}
          to={to}
          updateDateRange={(from, to) =>
            this.props.onChange({
              from: from.format(DATE_FORMAT2),
              to: to.format(DATE_FORMAT2)
            })
          }
        />
      );
    }
    if (this.props.source.activationStrategy === "HOUR_RANGE") {      
      label = "Hour range";            
      content = (
        <div className="row">
          <div className="col-md-6">
            <TimePicker
              label='Start at'
              hourOfDay={this.props.value.startAt}
              onChange={(timeOfDay) =>           
                  this.props.onChange({...this.props.value, startAt: timeOfDay})
              }
            />
          </div>
          <div className="col-md-6">
            <TimePicker
              label='End at'
              hourOfDay={this.props.value.endAt}
              onChange={(timeOfDay) =>           
                this.props.onChange({...this.props.value, endAt: timeOfDay})
              }
            />          
          </div>
        </div>
      );
    }
    if (this.props.source.activationStrategy === "SCRIPT") {
      return (
        <CodeInput
          parse={false}
          label="Script"
          debug={true}
          languages={{
            javascript: {
              label: "Javascript",
              snippet: ScriptsTemplate.javascriptDefaultScript
            },
            scala: {
              label: "Scala",
              snippet: ScriptsTemplate.scalaDefaultScript
            },
            kotlin: {
              label: "Kotlin",
              snippet: ScriptsTemplate.kotlinDefaultScript
            }
          }}
          default={"javascript"}
          value={this.props.value}
          onChange={value => this.props.onChange({ ...value })}
        />
      );
    }
    if (this.props.source.activationStrategy === "GLOBAL_SCRIPT") {
      return (
        <AsyncSelectInput
          label="Script"
          computeUrl={query => `/api/scripts?name_only=true&pattern=${query}*`}
          value={this.props.value.ref}
          extractResponse={r => r.results}
          onChange={r => this.props.onChange({ ref: r })}
        />
      );
    }
    if (this.props.source.activationStrategy === "PERCENTAGE") {
      return (
        <PercentageInput
          placeholder={"50"}
          value={this.props.value.percentage}
          label={"Percentage"}
          onChange={r => this.props.onChange({ percentage: r })}
        />
      );
    }
    if (this.props.source.activationStrategy === "CUSTOMERS_LIST") {
      return (
        <ArrayInput
          value={this.props.value.customers}
          label={"Customers"}
          placeholder={"customerId"}
          onChange={c => this.props.onChange({ customers: c })}
        />
      );
    }
    return (
      <div className="form-group row">
        <label
          htmlFor={`input-${this.props.label}`}
          className="col-sm-2 col-form-label"
        >
          {label}
        </label>
        <div className="col-sm-10">{content}</div>
      </div>
    );
  }
}

export class FeaturesPage extends Component {
  formSchema = {
    id: {
      type: "key",
      props: {
        label: "Feature Id",
        placeholder: "The Feature id",
        search(pattern) {
          return IzanamiServices.fetchFeatures({
            page: 1,
            pageSize: 20,
            search: pattern
          }).then(({ results }) => results.map(({ id }) => id));
        }
      },
      error: { key: "obj.id" }
    },
    activationStrategy: {
      type: "select",
      props: {
        label: "Feature strategy",
        placeholder: "The Feature strategy",
        possibleValues: [
          "NO_STRATEGY",
          "PERCENTAGE",
          "RELEASE_DATE",
          "DATE_RANGE",
          "HOUR_RANGE",
          "SCRIPT",
          "GLOBAL_SCRIPT",
          "CUSTOMERS_LIST"
        ]
      },
      error: { key: "obj.activationStrategy" }
    },
    description: {
      type: "string", 
      props: { label: "Feature description", placeholder: `Description` },
      error: { key: "obj.description" }
    },
    enabled: {
      type: "bool",
      props: { label: "Feature active", placeholder: `Feature active` },
      error: { key: "obj.enabled" }
    },
    parameters: {
      type: FeatureParameters,
      props: {
        label: "Parameters",
        placeholderKey: "Parameter name",
        placeholderValue: "Parameter value"
      },
      error: { key: "obj.parameters" }
    }
  };

  searchKey = pattern => {
    return IzanamiServices.fetchFeatures({
      page: 1,
      pageSize: 20,
      search: pattern
    }).then(({ results }) => results.map(({ id }) => id));
  };

  editSchema = {
    ...this.formSchema,
    id: {
      ...this.formSchema.id,
      props: {
        ...this.formSchema.id.props,
        disabled: true,
        search: this.searchKey
      }
    }
  };

  renderStrategy = item => {
    const params = item.parameters || {};
    switch (item.activationStrategy) {
      case "SCRIPT":
        if (params.type === "javascript") {
          return (
            <span>
              <JsLogo width={"20px"} />
              {` Script`}
            </span>
          );
        } else if (params.type === "scala") {
          return (
            <span>
              <ScalaLogo width={"20px"} />
              {` Script`}
            </span>
          );
        } else if (params.type === "kotlin") {
          return (
            <span>
              <KotlinLogo width={"20px"} />
              {` Script`}
            </span>
          );
        }
      case "NO_STRATEGY":
        return <span>{`No strategy`}</span>;
      case "RELEASE_DATE":
        const mDate = moment(params.releaseDate, DATE_FORMAT);
        return (
          <span
            data-toggle="tooltip"
            data-placement="top"
            title={`Released on ${params.releaseDate}`}
          >
            <time dateTime={`${params.releaseDate}`} className="icon">
              <span>{mDate.format("DD")}</span>
              <span>{mDate.format("MMM")}</span>
              <span>{mDate.format("YYYY")}</span>
            </time>
          </span>
        );
      case "DATE_RANGE":
        return (
          <span
            style={{ textAlign: "center" }}
            data-toggle="tooltip"
            data-placement="top"
            title={`Enabled between ${params.from} and ${params.to}`}
          >
            <time
              dateTime={`${moment(params.from).format("YYYY-MM-DD")}`}
              className="icon"
            >
              <span>{moment(params.from).format("DD")}</span>
              <span>{moment(params.from).format("MMM")}</span>
              <span>{moment(params.from).format("YYYY")}</span>
            </time>
            <span>
              {" "}
              <i className="fas fa-arrow-right" />{" "}
            </span>
            <time
              dateTime={`${moment(params.to).format("YYYY-MM-DD")}`}
              className="icon"
            >
              <span>{moment(params.to).format("DD")}</span>
              <span>{moment(params.to).format("MMM")}</span>
              <span>{moment(params.to).format("YYYY")}</span>
            </time>
          </span>
        );
      case "HOUR_RANGE":
        return (
          <span
            style={{ textAlign: "center" }}
            data-toggle="tooltip"
            data-placement="top"
            title={`Enabled between ${params.startAt} and ${params.endAt}`}
          >
            {params.startAt} -> {params.endAt}
          </span>
        )
      case "GLOBAL_SCRIPT":
        return (
          <span>
            <i className="far fa-file-alt" aria-hidden="true" />
            {` Script based on '${params.ref}'`}
          </span>
        );
      case "PERCENTAGE":
        return (
          <span>Enabled for {`${params.percentage} % of the traffic`}</span>
        );
      case "CUSTOMERS_LIST":
        return (
          <span>Enabled for customers list</span>
        );
      default:
        return item.activationStrategy;
    }
  };

  renderIsActive = item => {
    const allowed = Abilitations.isUpdateAllowed(this.props.user, item.id);
    return (<SimpleBooleanInput
              disabled={!allowed}
              value={item.enabled}
              onChange={(v, input) => {
                let confirmRes = true;
                if (this.props.confirmationDialog) {
                  confirmRes = window.confirm("Are you sure you want to toggle " + item.id);
                }

                if (confirmRes === true) {
                  IzanamiServices.fetchFeature(item.id).then(feature => {
                    IzanamiServices.updateFeature(item.id, { ...feature, enabled: v })
                        .then(res => {
                          if (res.status === 403) {
                            input.setState({ enabled: !v });
                          }
                        });
                  });
                } else {
                  input.setState({ enabled: !v });
                }
              }}
            />)
  };

  columns = [
    {
      title: "Name",
      search: (s, item) => item.id.indexOf(s) > -1,
      content: item => (
        <Link to={`/features/edit/${item.id}`}>
          <Key value={item.id} />
        </Link>
      )
    },    
    {
      title: "Strategy",
      notFilterable: true,
      style: { textAlign: "center", width: 300 },
      content: this.renderStrategy
    },
    {
      title: "Active",
      style: { textAlign: "center", width: 60 },
      notFilterable: true,
      content: this.renderIsActive
    }
  ];

  formFlow = ["id", "enabled", "description", "---", "activationStrategy", "parameters"];

  fetchItems = args => {
    const { search = [], page, pageSize } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchFeatures({ page, pageSize, search: pattern });
  };

  fetchItemsTree = args => {
    const { search = [] } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchFeaturesTree({ search: pattern });
  };

  fetchItem = id => {
    return IzanamiServices.fetchFeature(id);
  };

  createItem = feature => {
    return IzanamiServices.createFeature(feature);
  };

  updateItem = (feature, featureOriginal) => {
    return IzanamiServices.updateFeature(featureOriginal.id, feature);
  };

  deleteItem = feature => {
    return IzanamiServices.deleteFeature(feature.id, feature);
  };

  componentDidMount() {
    this.props.setTitle("Features");
  }

  renderTreeLeaf = value => {
    return [
      <div
        key={`content-strategy-${value.id}`}
        className="content-value-items"
        style={{ width: 300 }}
      >
        <div>{this.renderStrategy(value)}</div>
      </div>,
      <div
        key={`active-strategy-${value.id}`}
        className="content-value-items"
        style={{ width: 60 }}
      >
        {this.renderIsActive(value)}
      </div>
    ];
  };

  itemLink = node => {
    if (node && node.value) {
      return `/features/edit/${node.id}`;
    } else if (node && node.id) {
      return `/features/add/${node.id}`;
    } else {
      return `/features/add`;
    }
  };

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={id => ({
              enabled: true,
              activationStrategy: "NO_STRATEGY",
              parameters: {},
              id: id || ""
            })}
            copyNodeWindow={true}
            copyNodes={(from, to, active) => IzanamiServices.copyFeatureNodes(from, to, active)}
            searchKeys={this.searchKey}
            treeModeEnabled={true}
            renderTreeLeaf={this.renderTreeLeaf}
            itemLink={this.itemLink}
            parentProps={this.props}
            user={this.props.user}
            defaultTitle="Features"
            selfUrl="features"
            backToUrl="features"
            itemName="Feature"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItemsTree={this.fetchItemsTree}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            onEvent={this.onEvent}
            showActions={true}
            showLink={false}
            eventNames={{
              created: "FEATURE_CREATED",
              updated: "FEATURE_UPDATED",
              deleted: "FEATURE_DELETED"
            }}
            downloadLinks={[
              { title: "Download", link: "/api/features.ndjson" }
            ]}
            uploadLinks={[
                { title: "Upload - replace if exists", link: "/api/features.ndjson?strategy=Replace" },
                { title: "Upload - ignore if exists", link: "/api/features.ndjson?strategy=Keep" }
            ]}
            extractKey={item => item.id}
            lockable={true}
            lockType={"feature"}
          />
        </div>
      </div>
    );
  }
}
