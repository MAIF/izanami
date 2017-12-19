import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table } from '../inputs';

export class GlobalScriptsPage extends Component {

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

  formSchema = {
    id: { type: 'string', props: { label: 'Script Id', placeholder: 'The Script id' }, error : { key : 'obj.id'}},
    name: { type: 'string', props: { label: 'Script name', placeholder: 'The Script name' }, error : { key : 'obj.name'}},
    description: { type: 'string', props: { label: 'Script description', placeholder: 'The Script description' }, error : { key : 'obj.description'}},
    source: { type: 'code', props: { label: 'Code', placeholder: `true` }, error : { key : 'obj.source'}},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.id, props: { ...this.formSchema.id.props, disabled: true } } };

  columns = [
    { title: 'Id', content: item => item.id },
    { title: 'Name', notFilterable: true, style: { textAlign: 'center'}, content: item => item.name },
    { title: 'Description', notFilterable: true, style: { textAlign: 'center'}, content: item => item.description },
  ];

  formFlow = [
    'id',
    'name',
    'description',
    'source',
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchScripts({page, pageSize, search: pattern }); 
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchScript(id);
  };

  createItem = (script) => {
    return IzanamiServices.createScript(script);
  };

  updateItem = (script, scriptOriginal) => {
    return IzanamiServices.updateScript(scriptOriginal.id, script);
  };

  deleteItem = (script) => {
    return IzanamiServices.deleteScript(script.id, script);
  };

  componentDidMount() {
    this.props.setTitle("Scripts");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              id: "project:script1",
              name: 'Authorization Script',
              description: 'Authorize all members of the project team',
              source: this.defaultScriptValue
            })}
            user={this.props.user}
            parentProps={this.props}
            defaultTitle="Scripts"
            selfUrl="scripts"
            backToUrl="scripts"
            itemName="Script"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{title: "Download", link: "/api/scripts.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/scripts.ndjson"}]}
            showActions={true}
            showLink={false}
            extractKey={item => item.id} />
        </div>
      </div>
    );
  }
}