import type { ReactNode } from 'react'
export function Badge({tone='',children}:{tone?:'ok'|'warn'|'bad'|'outline'|'';children:ReactNode}){return <span className={'v-badge '+tone}>{children}</span>}
export function Empty({title,children}:{title:string;children?:ReactNode}){return <div className="v-empty"><h3>{title}</h3><p>{children}</p></div>}
export function Skeleton({rows=4}:{rows?:number}){return <div className="stack" aria-busy="true" aria-label="Loading">{Array.from({length:rows},(_,i)=><span key={i} className="v-skel" style={{width:(90-i*10)+'%'}}/>)}</div>}
