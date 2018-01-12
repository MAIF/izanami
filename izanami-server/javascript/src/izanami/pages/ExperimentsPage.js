import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table, SimpleBooleanInput, TextInput, NumberInput} from '../inputs';
import {CartesianGrid, XAxis, YAxis, Tooltip, AreaChart, Area} from 'recharts'
import _ from 'lodash';
import moment from 'moment';

class Variant extends Component {
  render() {
    const variant = this.props.variant;
    return (
      <div>
        <hr />
        <TextInput label="Id" value={this.props.variant.id} onChange={value => this.props.onChange({ ...variant, id: value })} />
        <TextInput label="Name" value={this.props.variant.name} onChange={value => this.props.onChange({ ...variant, name: value })} />
        <TextInput label="Description" value={this.props.variant.description} onChange={value => this.props.onChange({ ...variant, description: value })} />
        <NumberInput label="Traffic" value={this.props.variant.traffic} onChange={value => this.props.onChange({ ...variant, traffic: value })} />
      </div>
    );
  }
}

class Variants extends Component {
  render() {
    const variants = [ ...this.props.source.variants ];
    variants.sort((a, b) => {
      return a.id.localeCompare(b.id);
    });
    return (
      <div>
        {variants.map(v => <Variant key={v.id} variant={v} onChange={variant => this.props.onChange([ ...this.props.value.filter(v => v.id !== variant.id), variant ])} />)}
      </div>
    );
  }
}

export class ExperimentsPage extends Component {

  colors = [
    '#95cf3d',
    '#027cc3',
    '#ff8900',
    '#d50200',
    '#7cb5ec',
    '#8085c9',
    '#ffeb3b',
    '#8a2be2',
    '#a52a2a',
    '#deb887',
  ];

  state = {
    results: null
  };

  formSchema = {
    id: { type: 'string', props: { label: 'Id', placeholder: 'The Experiment id' }, error : { key : 'obj.id'}},
    name: { type: 'string', props: { label: 'Name', placeholder: 'The Experiment name' }, error : { key : 'obj.name'}},
    description: { type: 'string', props: { label: 'Description', placeholder: 'The Experiment description' }, error : { key : 'obj.description'}},
    enabled: { type: 'bool', props: { label: 'Active', placeholder: `Experiment active` }, error : { key : 'obj.enabled'}},
    variants: { type: Variants, props: { label: 'Variants' }, error : { key : 'obj.variants'}},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.id, props: { ...this.formSchema.id.props, disabled: true } } };

  columns = [
    { title: 'Id', content: item => item.id },
    { title: 'Name', notFilterable: true, style: { textAlign: 'center'}, content: item => item.name},
    { title: 'Description', notFilterable: true, style: { textAlign: 'center', width: 300}, content: item => item.description },
    { title: 'Active', notFilterable: true, style: { textAlign: 'center', width: 50}, content: item => <SimpleBooleanInput value={item.enabled} onChange={v => {
      IzanamiServices.fetchExperiment(item.id).then(feature => {
        IzanamiServices.updateExperiment(item.id, { ...feature, enabled: v });
      })
    }} /> },
    {
      title: 'Results',
      style: { textAlign: 'center', width: 150, height: '40px'},
      notFilterable: true ,
      content: item =>
        <button type="button" className="btn btn-sm btn-success" onClick={e => this.showResults(e, item)}><i className="fa fa-line-chart" aria-hidden="true"></i> see report</button>
    },
  ];

  formFlow = [
    'id',
    'name',
    'description',
    'enabled',
    'variants'
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchExperiments({page, pageSize, search: pattern });
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchExperiment(id);
  };

  createItem = (experiment) => {
    return IzanamiServices.createExperiment(experiment);
  };

  updateItem = (experiment, experimentOriginal) => {
    return IzanamiServices.updateExperiment(experimentOriginal.id, experiment);
  };

  deleteItem = (experiment) => {
    return IzanamiServices.deleteExperiment(experiment.id, experiment);
  };

  closeResults = () => {
    this.setState({ results: null, item: null });
    this.props.setTitle("Experiments");
  };

  showResults = (e, item) => {
    IzanamiServices.fetchExperimentResult(item.id).then(results => {
      this.props.setTitle("Results for " + results.experiment.name);
      const [serieNames, data] = this.buildChartData(results);
      console.log("Results", serieNames, data);
      this.setState({ results, item, serieNames, data }, () => {
        //this.mountChart(this.chartRef)
      });
    });
  };

  buildChartData = ({results}) => {
    let serieNames = results.map(res => [res.variant.id, `${res.variant.name} (${res.variant.id})`]);

    let evts = results.flatMap(res =>
      res.events.map(e => ({
          variant: e.variantId,
          name: moment(e.date).format('YYYY-MM-DD HH:mm'),
          label: `${res.variant.name} (${res.variant.id})`,
          date: e.date,
          transformation: parseFloat(e.transformation.toFixed(2)),
          [e.variantId]: parseFloat(e.transformation.toFixed(2))
        }))
    );
    evts = _.sortBy(evts, 'date');

    results.forEach(res => {
      let transfo = 0.0;
      evts.forEach(e => {
        console.log(e, res);
        if (e.variant !== res.variant.id) {
          e[res.variant.id] = parseFloat(transfo.toFixed(2));
        } else {
          transfo = e.transformation;
        }
      });
    });
    return [serieNames, evts];
  };

  componentDidMount() {
    this.props.setTitle("Experiments");
  }

  render() {
    const results = (this.state.results || { results: []}).results;
    results.sort((a, b) => a.variant.id.localeCompare(b.variant.id));
    const population = results.reduce((a, b) => a + b.variant.currentPopulation, 0);
    return (
      <div className="col-md-12">
        {!this.state.results && (
          <div className="row">
            <Table
              defaultValue={() => ({
                id: 'project:experiments:name',
                name: 'My First experiment',
                description: 'See what people like the most about ...',
                enabled: true,
                variants: [
                  {
                    id: 'A',
                    name: 'Variant A',
                    description: 'Variant A is about ...',
                    traffic: 0.5
                  },
                  {
                    id: 'B',
                    name: 'Variant B',
                    description: 'Variant B is about ...',
                    traffic: 0.5
                  }
                ]
              })}
              parentProps={this.props}
              user={this.props.user}
              defaultTitle="Experiments"
              selfUrl="experiments"
              backToUrl="experiments"
              itemName="Experiment"
              formSchema={this.formSchema}
              editSchema={this.editSchema}
              formFlow={this.formFlow}
              columns={this.columns}
              fetchItems={this.fetchItems}
              fetchItem={this.fetchItem}
              updateItem={this.updateItem}
              deleteItem={this.deleteItem}
              createItem={this.createItem}
              downloadLinks={[
                {title: "DL experiments", link: "/api/experiments.ndjson"},
                {title: "DL bindings", link: "/api/experiments/bindings.ndjson"},
                {title: "DL events", link: "/api/experiments/events.ndjson"},
              ]}
              uploadLinks={[
                {title: "UL experiments", link: "/api/experiments.ndjson"},
                {title: "UL bindings", link: "/api/experiments/bindings.ndjson"},
                {title: "UL events", link: "/api/experiments/events.ndjson"},
              ]}
              eventNames={{
                created: 'EXPERIMENT_CREATED',
                updated: 'EXPERIMENT_UPDATED',
                deleted: 'EXPERIMENT_DELETED'
              }}
              showActions={true}
              showLink={false}
              extractKey={item => item.id} />
          </div>)}
        {this.state.results && (
          <div className="row">
            <form className="form-horizontal">
              {this.state.results.winner && (<p>
                The winner of the experiment name <span className="bold">"{this.state.results.experiment.name}"</span> is <span className="bold">"{this.state.results.winner.name}"</span> (<span className="bold">{this.state.results.winner.id}</span>)
              </p>)}
              {!this.state.results.winner && (<p>
                There is no winner yet for the experiment name <span className="bold">"{this.state.results.experiment.name}"</span>
              </p>)}
              <hr />
              <p>
                Tested population consist of <span className="bold">{population}</span> users with <span className="bold">{displays}</span> displays
              </p>
              <hr />
              <ul>
                {results.map((r, index) => (
                  <li key={index}>
                    Variant <span className="bold">"{r.variant.name}" ({r.variant.id})</span> has a conversion rate of <span className="bold">{r.transformation.toFixed(3)} %</span>
                    <ul>
                      <li>won <span className="bold">{r.won}</span> times over <span className="bold">{r.displayed}</span> displays</li>
                    </ul>
                  </li>
                ))}
              </ul>
            </form>
            <AreaChart width={800} height={400} data={this.state.data} >
              {this.state.serieNames.map(([k, s], i) =>
                <Area
                  type="monotone"
                  key={k}
                  dataKey={k}
                  unit={" %."}
                  stroke={this.colors[i]}
                  fillOpacity={0.6}
                  fill={this.colors[i]}
                />
              )}
              <Tooltip />
              <CartesianGrid stroke="#ccc" strokeDasharray="5 5" />
              <XAxis dataKey="name" />
              <YAxis />
            </AreaChart>
            <div className="modal-footer">
              <button type="button" className="btn btn-primary" onClick={e => this.showResults(e, this.state.item)}><i className="glyphicon glyphicon-refresh" /> Reload</button>
              <button type="button" className="btn btn-danger" onClick={this.closeResults}>Close</button>
            </div>
          </div>
        )}
      </div>
    );
    const displays = results.reduce((a, b) => a + b.displayed, 0);
  }
}
