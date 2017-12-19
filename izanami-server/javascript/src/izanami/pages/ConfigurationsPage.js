import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table } from '../inputs';

export class ConfigurationsPage extends Component {

  formSchema = {
    id: { type: 'string', props: { label: 'Feature Id', placeholder: 'The Feature id' }, error : { key : 'obj.id'}},
    value: { type: 'code', props: { parse: true, label: 'Value', placeholder: `true` }, error : { key : 'obj.value'}},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.id, props: { ...this.formSchema.id.props, disabled: true } } };

  columns = [
    { title: 'Name', content: item => item.id },
  ];

  formFlow = [
    'id',
    'value'
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchConfigs({page, pageSize, search: pattern });  
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchConfig(id);
  };

  createItem = (config) => {
    return IzanamiServices.createConfig(config);
  };

  updateItem = (config, configOriginal) => {
    return IzanamiServices.updateConfig(configOriginal.id, config);
  };

  deleteItem = (config) => {
    return IzanamiServices.deleteConfig(config.id, config);
  };

  componentDidMount() {
    this.props.setTitle("Configurations");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              value: '{"key":"value"}',
              id: "project:env:config1"
            })}
            user={this.props.user}
            parentProps={this.props}
            defaultTitle="Configurations"
            selfUrl="configurations"
            backToUrl="configurations"
            itemName="Configuration"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{title: "Download", link: "/api/configs.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/configs.ndjson"}]}
            showActions={true}
            showLink={false}
            extractKey={item => item.id} />
        </div>
      </div>
    );
  }
}