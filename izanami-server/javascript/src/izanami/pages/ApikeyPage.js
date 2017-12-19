import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table } from '../inputs';
import faker from 'faker';

export class ApikeyPage extends Component {


  formSchema = {
    name: { type: 'string', props: { label: 'Api key name', placeholder: 'The Api key name' }},
    clientId: { type: 'string', props: { label: 'Client Id', placeholder: 'The Client Id' }},
    clientSecret: { type: 'string', props: { label: 'Client Secret', placeholder: 'The Client Secret' }},
    authorizedPattern: { type: 'string', props: { label: 'Authorized pattern', placeholder: 'The Authorized pattern' }},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.clientId, props: { ...this.formSchema.clientId.props, disabled: true } } };

  columns = [
    { title: 'Name', notFilterable: true, content: item => item.name },
    { title: 'Client id', notFilterable: true, content: item => item.clientId },
    { title: 'Client secret', notFilterable: true, content: item => item.clientSecret},
    { title: 'Authorized pattern', notFilterable: true, content: item => item.authorizedPattern},
  ];

  formFlow = [
    'name',
    'clientId',
    'clientSecret',
    'authorizedPattern'
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchApikeys({page, pageSize, search: pattern });  
  };
  
  fetchItem = (id) => {
    return IzanamiServices.fetchApikey(id);
  };

  createItem = (apikey) => {
    return IzanamiServices.createApikey(apikey);
  };

  updateItem = (apikey, apiKeyOriginal) => {
    return IzanamiServices.updateApikey(apiKeyOriginal.clientId, apikey);
  };

  deleteItem = (apikey) => {
    return IzanamiServices.deleteApikey(apikey.clientId, apikey);
  };

  componentDidMount() {
    this.props.setTitle("Apikeys");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              name : "Apikey 1",
              clientId: faker.random.alphaNumeric(16),
              clientSecret: faker.random.alphaNumeric(64),
              authorizedPattern : "*"
            })}
            parentProps={this.props}
            user={this.props.user}
            defaultTitle="Apikeys"
            selfUrl="apikeys"
            backToUrl="apikeys"
            itemName="apikey"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            showActions={true}
            showLink={false}
            extractKey={item => item.clientId}
            downloadLinks={[{title: "Download", link: "/api/apikeys.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/apikeys.ndjson"}]}
          />
        </div>
      </div>
    );
  }
}