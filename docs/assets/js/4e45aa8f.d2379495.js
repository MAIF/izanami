"use strict";(self.webpackChunkizanami_documentation=self.webpackChunkizanami_documentation||[]).push([[4499],{7772:(i,e,n)=>{n.r(e),n.d(e,{assets:()=>d,contentTitle:()=>l,default:()=>g,frontMatter:()=>s,metadata:()=>c,toc:()=>u});var o=n(5893),t=n(1151);const r=n.p+"assets/images/configuration-screen-a1785360628847e3706e0b3d86635658.png",a=n.p+"assets/images/invitation-method-217e5a0f32e75c0380ee0e45a4e65bd1.png",s={title:"Configuring mailer"},l=void 0,c={id:"guides/mailer-configuration",title:"Configuring mailer",description:"Izanami can send email, for example, for user invitations or password reset.",source:"@site/docs/04-guides/11-mailer-configuration.mdx",sourceDirName:"04-guides",slug:"/guides/mailer-configuration",permalink:"/izanami/docs/guides/mailer-configuration",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:11,frontMatter:{title:"Configuring mailer"},sidebar:"tutorialSidebar",previous:{title:"Creating API key",permalink:"/izanami/docs/guides/key-configuration"},next:{title:"Configuring Izanami",permalink:"/izanami/docs/guides/configuration"}},d={},u=[{value:"Configuring mail provider",id:"configuring-mail-provider",level:2},{value:"Configuring invitation mode",id:"configuring-invitation-mode",level:2}];function m(i){const e={a:"a",h2:"h2",li:"li",p:"p",ul:"ul",...(0,t.a)(),...i.components};return(0,o.jsxs)(o.Fragment,{children:[(0,o.jsx)(e.p,{children:"Izanami can send email, for example, for user invitations or password reset."}),"\n",(0,o.jsx)(e.p,{children:"It supports 4 mail providers :"}),"\n",(0,o.jsxs)(e.ul,{children:["\n",(0,o.jsx)(e.li,{children:"Console: this is the default when no mail provider is configured, mails will be displayed in application logs (except for invitations, see below)"}),"\n",(0,o.jsxs)(e.li,{children:["MailJet : ",(0,o.jsx)(e.a,{href:"https://www.mailjet.com/",children:"MailJet"})," external provider"]}),"\n",(0,o.jsxs)(e.li,{children:["MailGun : ",(0,o.jsx)(e.a,{href:"https://www.mailgun.com/",children:"MailGun"})," external provider"]}),"\n",(0,o.jsx)(e.li,{children:"SMTP : allow to manually configure another SMTP server"}),"\n"]}),"\n",(0,o.jsxs)(e.p,{children:["For invitations, Izanami let you choose between sending them by mail or displaying invitation link in the UI. See ",(0,o.jsx)(e.a,{href:"./user-invitation",children:"invitation guide"}),"."]}),"\n",(0,o.jsx)(e.h2,{id:"configuring-mail-provider",children:"Configuring mail provider"}),"\n",(0,o.jsx)(e.p,{children:'To configure mail provider, you need to be Izanami admin. Just go to the "Global Settings" page and set up the mail provider of your choice.'}),"\n",(0,o.jsx)(e.p,{children:"You must also configure origin email, which indicates origin sender email to use."}),"\n",(0,o.jsx)("img",{src:r}),"\n",(0,o.jsx)(e.h2,{id:"configuring-invitation-mode",children:"Configuring invitation mode"}),"\n",(0,o.jsx)(e.p,{children:"Default invitation mode is to display an invitation link to the user performing the invitation."}),"\n",(0,o.jsx)(e.p,{children:"This allows Izanami to work correctly even when no mailer is configured."}),"\n",(0,o.jsx)(e.p,{children:"It is possible to change this behavior to make Izanami send these invitation emails."}),"\n",(0,o.jsx)("img",{src:a})]})}function g(i={}){const{wrapper:e}={...(0,t.a)(),...i.components};return e?(0,o.jsx)(e,{...i,children:(0,o.jsx)(m,{...i})}):m(i)}},1151:(i,e,n)=>{n.d(e,{Z:()=>s,a:()=>a});var o=n(7294);const t={},r=o.createContext(t);function a(i){const e=o.useContext(r);return o.useMemo((function(){return"function"==typeof i?i(e):{...e,...i}}),[e,i])}function s(i){let e;return e=i.disableParentContext?"function"==typeof i.components?i.components(t):i.components||t:a(i.components),o.createElement(r.Provider,{value:e},i.children)}}}]);