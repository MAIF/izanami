import React, { Component } from "react";
import {Key} from "./Key";


const intersperse = (arr, sep) => arr.reduce((a,v,i)=>[...a,v,sep(i)],[]).slice(0,-1)

const Letter = props => (
    <span style={{
              margin:'1px',
              padding: '1px',
              fontSize: '14px',
              fontWeight: '300',
              border: '1px solid #419641',
              color: '#fff',
              backgroundColor: props.selected && '#419641'
          }}
    >{props.value}</span>
)

export const AuthorizedPatterns = (props) => {

    return intersperse(props.value.map( (p, i) =>
        <span key={`pattern-${i}-${p.pattern}`}>
            <Key value={p.pattern}/>
            <span>  </span>
            <div className="btn-group btn-group-xs" role="group" >
                <Letter value={'C'} selected={p.rights.includes('C')} />
                <Letter value={'R'} selected={p.rights.includes('R')} />
                <Letter value={'U'} selected={p.rights.includes('U')} />
                <Letter value={'D'} selected={p.rights.includes('D')} />
            </div>
        </span>
    ), i => <span key={`pattern-${i}-sep}`}> </span>)

};