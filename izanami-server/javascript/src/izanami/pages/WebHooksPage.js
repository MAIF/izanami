import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import {Table, SimpleBooleanInput, TextInput} from "../inputs";
import faker from "@faker-js/faker";

export class WebHooksPage extends Component {
  formSchema = {
    clientId: {
      type: "string",
      props: { label: "WebHook Client Id", placeholder: "abcdefg" },
      error: { key: "obj.clientId" }
    },
    callbackUrl: {
      type: "string",
      props: {
        label: "WebHook URL",
        placeholder: "The URL call for each notification"
      },
      error: { key: "obj.callbackUrl" }
    },
    patterns: {
      type: "string",
      props: {
        label: "WebHook pattern",
        placeholder: "Pattern to filter notifications events"
      },
      error: { key: "obj.patterns" }
    },
    headers: {
      type: "object",
      props: {
        label: "WebHook Headers",
        placeholderKey: "Header name",
        placeholderValue: "Header value"
      },
      error: { key: "obj.headers" }
    }
  };

  editSchema = {
    ...this.formSchema,
    clientId: {
      ...this.formSchema.clientId,
      props: { ...this.formSchema.clientId.props, disabled: true }
    }
  };

  columns = [
    { title: "ID",
      content: item => item.clientId },
    {
      title: "Pattern",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => {
        return (item.patterns || []).join(",");
      }
    },
    {
      title: "URL",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => item.callbackUrl
    },
    {
      title: "Banned",
      notFilterable: true,
      style: { textAlign: "center", width: 70 },
      content: item => (
        <SimpleBooleanInput
          value={!item.isBanned}
          onChange={v => {
            IzanamiServices.fetchWebhook(item.clientId).then(webhook => {
              IzanamiServices.updateWebHook(item.clientId, {
                ...webhook,
                isBanned: !v
              });
            });
          }}
        />
      )
    }
  ];

  formFlow = ["clientId", "patterns", "---", "callbackUrl", "headers"];

  fetchItems = args => {
    const { search = [], page, pageSize } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchWebHooks({ page, pageSize, search: pattern });
  };

  fetchItem = id => {
    return IzanamiServices.fetchWebhook(id);
  };

  createItem = webhook => {
    const patterns = (webhook.patterns || "").split(",");
    return IzanamiServices.createWebHook({ ...webhook, patterns });
  };

  updateItem = (webhook, webhookOriginal) => {
    const patterns = (webhook.patterns || "").split(",");
    return IzanamiServices.updateWebHook(webhookOriginal.clientId, {
      ...webhook,
      patterns
    });
  };

  deleteItem = webhook => {
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
              callbackUrl: "",
              notificationPattern: "*",
              headers: {},
              isBanned: false
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
            convertItem={(item) => {
              const convertedItem = item
              convertedItem.patterns = (convertedItem.patterns || "").join(",")
              return convertedItem
            }}
            downloadLinks={[
              { title: "Download", link: "/api/webhooks.ndjson" }
            ]}
            uploadLinks={[
              { title: "Upload - replace if exists", link: "/api/webhooks.ndjson?strategy=Replace" },
              { title: "Upload - ignore if exists", link: "/api/webhooks.ndjson?strategy=Keep" }
            ]}
            showActions={true}
            showLink={false}
            eventNames={{
              created: "WEBHOOK_CREATED",
              updated: "WEBHOOK_UPDATED",
              deleted: "WEBHOOK_DELETED"
            }}
            extractKey={item => (item ? item.clientId : null)}
            navigate={this.props.navigate}
          />
        </div>
      </div>
    );
  }
}
