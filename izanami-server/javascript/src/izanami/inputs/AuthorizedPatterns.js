import React, { Component } from "react";
import AceEditor from "react-ace";
import {Key} from "./Key";


const intersperse = (arr, sep) => arr.reduce((a,v,i)=>[...a,v,sep(i)],[]).slice(0,-1)

export const AuthorizedPatterns = (props) => {

    return intersperse(props.value.map( (p, i) =>
        <span key={`pattern-${i}-${p.pattern}`}>
            <Key value={p.pattern}/>
            <span>  </span>
            <div className="btn-group btn-group-xs" role="group" >
            {p.rights.map(r => <button key={`pattern-${i}-${p.pattern}-btn-${r}`} style={{margin:'0px'}} className={"btn btn-success"}>{r}</button>)}
            </div>
        </span>
    ), i => <span key={`pattern-${i}-sep}`}> | </span>)

};