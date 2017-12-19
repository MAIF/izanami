import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table } from '../inputs';
import faker from 'faker';

export class WebHooksPage extends Component {

  formSchema = {
    clientId:            { type: 'string', props: { label: 'WebHook Client Id', placeholder: 'abcdefg' }, error : { key : 'obj.clientId'}},
    callbackUrl:         { type: 'string', props: { label: 'WebHook URL', placeholder: 'The URL call for each notification' }, error : { key : 'obj.callbackUrl'}},
    notificationPattern: { type: 'string', props: { label: 'WebHook pattern', placeholder: 'Pattern to filter notifications events' }, error : { key : 'obj.notificationPattern'}},
    headers:             { type: 'object', props: { label: 'WebHook Headers', placeholderKey: 'Header name', placeholderValue: 'Header value' }, error : { key : 'obj.headers'}},
  };

  editSchema = { ...this.formSchema, clientId: { ...this.formSchema.clientId, props: { ...this.formSchema.clientId.props, disabled: true } } };

  columns = [
    { title: 'ID', content: item => item.clientId },
    { title: 'Pattern', notFilterable: true, style: { textAlign: 'center'}, content: item => item.notificationPattern },
    { title: 'URL', notFilterable: true, style: { textAlign: 'center'}, content: item => item.callbackUrl },
  ];

  formFlow = [
    'clientId',
    'notificationPattern',
    '---',
    'callbackUrl',
    'headers',
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchWebHooks({page, pageSize, search: pattern }); 
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchWebHook(id);
  };

  createItem = (webhook) => {
    return IzanamiServices.createWebHook(webhook);
  };

  updateItem = (webhook, webhookOriginal) => {
    return IzanamiServices.updateWebHook(webhookOriginal.clientId, webhook);
  };

  deleteItem = (webhook) => {
    return IzanamiServices.deleteWebHook(webhook.clientId, webhook);
  };

  componentDidMount() {
    this.props.setTitle("WebHooks");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              clientId: faker.random.alphaNumeric(32),
              callbackUrl: '',
              notificationPattern: "*",
              headers: {}
            })}
            parentProps={this.props}
            user={this.props.user}
            defaultTitle="WebHooks"
            selfUrl="webhooks"
            backToUrl="webhooks"
            itemName="WebHook"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}      
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{title: "Download", link: "/api/webhooks.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/webhooks.ndjson"}]}
            showActions={true}
            showLink={false}
            extractKey={item => item ? item.clientId : null}/>
        </div>
      </div>
    );
  }
}