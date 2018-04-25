import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import {Key, Table, CodeInput, ObjectInput, SimpleBooleanInput, AsyncSelectInput, TextInput, PercentageInput} from '../inputs';
import moment from 'moment';
import {IzaDatePicker, IzaDateRangePicker} from '../components/IzanamiDatePicker';

const DATE_FORMAT = 'DD/MM/YYYY HH:mm:ss';
const DATE_FORMAT2 = 'YYYY-MM-DD HH:mm:ss';

class FeatureParameters extends Component {

  defaultScriptValue = `/**
 * context:  a JSON object containing app specific value
 *           to evaluate the state of the feature
 * enabled:  a callback to mark the feature as active
 *           for this request
 * disabled: a callback to mark the feature as inactive
 *           for this request
 * http:     a http client
 */
function enabled(context, enabled, disabled, http) {
  if (context.user === 'john.doe@gmail.com') {
    return enabled();
  }
  return disabled();
}`;

  componentWillReceiveProps(nextProps) {
    const oldStrategy = this.props.source.activationStrategy;
    const nextStrategy = nextProps.source.activationStrategy;
    if (nextStrategy !== oldStrategy) {
      if (oldStrategy === 'RELEASE_DATE') {
        this.props.onChange({ releaseDate: undefined });
      } else if (oldStrategy === 'SCRIPT') {
        this.props.onChange({ script: undefined });
      } else if (oldStrategy === 'GLOBAL_SCRIPT') {
        this.props.onChange({ ref: undefined });
      } else if (nextStrategy === 'PERCENTAGE') {
        this.props.onChange({ percentage: undefined });
      }
      if (nextStrategy === 'SCRIPT') {
        this.props.onChange({ script: this.defaultScriptValue });
      } else if (nextStrategy === 'RELEASE_DATE') {
        this.props.onChange({ releaseDate: moment().format(DATE_FORMAT) });
      } else if (nextStrategy === 'PERCENTAGE') {
        this.props.onChange({ percentage: 50 });
      } else if (nextStrategy === 'NO_STRATEGY') {
        this.props.onChange({});
      }
    }
  }

  render() {
    let content = <h1>No content ...</h1>;
    let label = 'Parameters';
    if (this.props.source.activationStrategy === 'NO_STRATEGY') {
      return <ObjectInput label="Parameters" placeholderKey="Parameter name" placeholderValue="Parameter value" value={this.props.value} onChange={this.props.onChange} />;
    }
    if (this.props.source.activationStrategy === 'RELEASE_DATE') {
      label = 'Release date';
      const date = this.props.value.releaseDate ? moment(this.props.value.releaseDate, DATE_FORMAT) : moment();
      content = <IzaDatePicker
          date={date}
          updateDate={d => this.props.onChange({ releaseDate : d.format(DATE_FORMAT) })}
      />;
    }
    if (this.props.source.activationStrategy === 'DATE_RANGE') {
      label = 'Date range';
      const from = this.props.value.from ? moment(this.props.value.from, DATE_FORMAT2) : moment();
      const to = this.props.value.to ? moment(this.props.value.to, DATE_FORMAT2) : moment().add(1, "day");
      content = <IzaDateRangePicker
          from={from}
          to={to}
          updateDateRange={ (from, to) =>
            this.props.onChange({ from : from.format(DATE_FORMAT2), to: to.format(DATE_FORMAT2) })
          }
      />;
    }
    if (this.props.source.activationStrategy === 'SCRIPT') {
      return <CodeInput parse={false} label="Script" value={this.props.value.script || this.defaultScriptValue} onChange={v => this.props.onChange({ script: v })} />;
    }
    if (this.props.source.activationStrategy === 'GLOBAL_SCRIPT') {
      return <AsyncSelectInput
                label="Script"
                computeUrl={query => `/api/scripts?name_only=true&pattern=${query}*`}
                value={this.props.value.ref}
                extractResponse={r => r.results}
                onChange={r => this.props.onChange({ ref: r })}
              />;
    }
    if (this.props.source.activationStrategy === 'PERCENTAGE') {
      return <PercentageInput
          placeholder={"50"}
          value={this.props.value.percentage}
          label={"Percentage"}
          onChange={r => this.props.onChange({ percentage: r })}
      />
    }
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-sm-2 control-label">{label}</label>
        <div className="col-sm-10">
          {content}
        </div>
      </div>
    );
  }
}

export class FeaturesPage extends Component {

  formSchema = {
    id: {
      type: 'key',
      props: {
        label: 'Feature Id',
        placeholder: 'The Feature id',
        search(pattern) {
          return IzanamiServices.fetchFeatures({page: 1, pageSize: 20, search: pattern })
            .then(({results}) =>
              results.map(({id}) => id)
            )
        }
      } ,
      error : { key : 'obj.id'}
    },
    activationStrategy: { type: 'select', props: { label: 'Feature strategy', placeholder: 'The Feature strategy', possibleValues: ['NO_STRATEGY', 'PERCENTAGE', 'RELEASE_DATE', 'DATE_RANGE', 'SCRIPT', 'GLOBAL_SCRIPT'] }, error : { key : 'obj.activationStrategy'}},
    enabled: { type: 'bool', props: { label: 'Feature active', placeholder: `Feature active` }, error : { key : 'obj.enabled'}},
    parameters: { type: FeatureParameters, props: { label: 'Parameters', placeholderKey: 'Parameter name', placeholderValue: 'Parameter value' }, error : { key : 'obj.parameters'}},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.id, props: { ...this.formSchema.id.props, disabled: true, search: this.searchKey } } };

  columns = [
    {
      title: 'Name',
      style: { width: 600},
      search: (s, item) => item.id.indexOf(s) > -1,
      content: item => <Key value={item.id} /> },
    {
      title: 'Strategy',
      notFilterable: true,
      style: { textAlign: 'center', width: 300},
      content: item => {
        const params = item.parameters || {};
        switch(item.activationStrategy) {
          case "SCRIPT":
            return <span><i className="fa fa-file-text-o" aria-hidden="true"/>{` Script`}</span>;
          case "NO_STRATEGY":
            return  <span>{`No strategy`}</span>;
          case "RELEASE_DATE":
            return <span><i className="fa fa-calendar" aria-hidden="true"/>{` released on ${params.releaseDate}`}</span>;
          case "DATE_RANGE":
            return (
                <span data-toggle="tooltip" data-placement="top" title={`${params.from} to ${params.to}`}>
                    <i className="fa fa-calendar" aria-hidden="true"/>{` from ${moment(params.from).format('YYYY-MM-DD')} to ${moment(params.to).format('YYYY-MM-DD')}`}
                </span>
            );
          case "GLOBAL_SCRIPT":
            return <span><i className="fa fa-file-text-o" aria-hidden="true"/>{` Script based on '${params.ref}'`}</span>;
          case "PERCENTAGE":
            return <span>Enabled for {`${params.percentage} % of the traffic`}</span>;
          default:
            return item.activationStrategy;
        }
      }
    },
    {
      title: 'Active',
      style: { textAlign: 'center', width: 60 },
      notFilterable: true ,
      content: item => <SimpleBooleanInput value={item.enabled} onChange={v => {
        IzanamiServices.fetchFeature(item.id).then(feature => {
          IzanamiServices.updateFeature(item.id, { ...feature, enabled: v });
        })
      }} />
    },
  ];

  formFlow = [
    'id',
    'enabled',
    '---',
    'activationStrategy',
    'parameters'
  ];

  searchKey = (pattern) => {
    return IzanamiServices.fetchFeatures({page: 1, pageSize: 20, search: pattern })
      .map(({results}) =>
        results.map(({id}) => id)
      )
  };

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchFeatures({page, pageSize, search: pattern });
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchFeature(id);
  };

  createItem = (feature) => {
    console.log('feature', feature);
    return IzanamiServices.createFeature(feature);
  };

  updateItem = (feature, featureOriginal) => {
    return IzanamiServices.updateFeature(featureOriginal.id, feature);
  };

  deleteItem = (feature) => {
    return IzanamiServices.deleteFeature(feature.id, feature);
  };

  componentDidMount() {
    this.props.setTitle("Features");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              enabled: true,
              activationStrategy: "NO_STRATEGY",
              parameters: {},
              id: ""
            })}
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
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            onEvent={this.onEvent}
            showActions={true}
            showLink={false}
            eventNames={{
              created: 'FEATURE_CREATED',
              updated: 'FEATURE_UPDATED',
              deleted: 'FEATURE_DELETED'
            }}
            downloadLinks={[{title: "Download", link: "/api/features.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/features.ndjson"}]}
            extractKey={item => item.id} />
        </div>
      </div>
    );
  }
}
