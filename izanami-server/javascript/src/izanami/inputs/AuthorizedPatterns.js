import React, { Component } from "react";
import {Key} from "./Key";


const intersperse = (arr, sep) => arr.reduce((a,v,i)=>[...a,v,sep(i)],[]).slice(0,-1)

const Letter = props => (
    <span className="letter"  style={{backgroundColor: props.selected && '#419641'}}>
        {props.value}
    </span>
)

export const AuthorizedPatterns = (props) => {

    return intersperse(props.value.map( (p, i) =>
        <span className="d-flex align-items-center justify-content-center" key={`pattern-${i}-${p.pattern}`}>
            <Key value={p.pattern}/>
            <span>  </span>
            <div className="btn-group btn-group-xs letter-group" role="group" >
                <Letter value={'C'} selected={p.rights.includes('C')} />
                <Letter value={'R'} selected={p.rights.includes('R')} />
                <Letter value={'U'} selected={p.rights.includes('U')} />
                <Letter value={'D'} selected={p.rights.includes('D')} />
            </div>
        </span>
    ), i => <span key={`pattern-${i}-sep}`}> </span>)

};