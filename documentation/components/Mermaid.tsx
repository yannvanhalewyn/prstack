'use client';

import { useEffect, useRef } from 'react';
import mermaid from 'mermaid';

export interface MermaidProps {
  chart: string;
}

export function Mermaid({ chart }: MermaidProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'dark',
      themeVariables: {
        darkMode: true,
        background: '#0a0a14',
        primaryColor: '#22d3ee',
        primaryTextColor: '#d4d4e8',
        primaryBorderColor: '#252547',
        secondaryColor: '#c084fc',
        secondaryTextColor: '#d4d4e8',
        secondaryBorderColor: '#252547',
        tertiaryColor: '#1a1a3e',
        tertiaryTextColor: '#d4d4e8',
        tertiaryBorderColor: '#252547',
        lineColor: '#8484a8',
        textColor: '#d4d4e8',
        mainBkg: '#13132b',
        secondBkg: '#1a1a3e',
        mainContrastColor: '#0a0a14',
        darkTextColor: '#0a0a14',
        border1: '#252547',
        border2: '#3d3d5c',
        arrowheadColor: '#22d3ee',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
        fontSize: '16px',
        nodeBorder: '#22d3ee',
        clusterBkg: '#13132b',
        clusterBorder: '#252547',
        defaultLinkColor: '#8484a8',
        titleColor: '#ebebf5',
        edgeLabelBackground: '#1a1a3e',
        actorBorder: '#22d3ee',
        actorBkg: '#13132b',
        actorTextColor: '#d4d4e8',
        actorLineColor: '#8484a8',
        signalColor: '#d4d4e8',
        signalTextColor: '#d4d4e8',
        labelBoxBkgColor: '#1a1a3e',
        labelBoxBorderColor: '#252547',
        labelTextColor: '#d4d4e8',
        loopTextColor: '#d4d4e8',
        noteBorderColor: '#c084fc',
        noteBkgColor: '#1a1a3e',
        noteTextColor: '#d4d4e8',
        activationBorderColor: '#22d3ee',
        activationBkgColor: '#13132b',
        sequenceNumberColor: '#0a0a14',
        sectionBkgColor: '#1a1a3e',
        altSectionBkgColor: '#13132b',
        sectionBkgColor2: '#252547',
        taskBorderColor: '#22d3ee',
        taskBkgColor: '#13132b',
        taskTextColor: '#d4d4e8',
        taskTextLightColor: '#d4d4e8',
        taskTextOutsideColor: '#d4d4e8',
        taskTextClickableColor: '#22d3ee',
        activeTaskBorderColor: '#c084fc',
        activeTaskBkgColor: '#1a1a3e',
        gridColor: '#252547',
        doneTaskBkgColor: '#0891b2',
        doneTaskBorderColor: '#06b6d4',
        critBorderColor: '#ec4899',
        critBkgColor: '#f472b6',
        todayLineColor: '#fbbf24',
        labelColor: '#0a0a14',
        errorBkgColor: '#991b1b',
        errorTextColor: '#fecaca',
        classText: '#d4d4e8',
        fillType0: '#13132b',
        fillType1: '#1a1a3e',
        fillType2: '#252547',
        fillType3: '#3d3d5c',
        fillType4: '#5a5a7a',
        fillType5: '#8484a8',
        fillType6: '#b0b0c8',
        fillType7: '#d4d4e8',
        // Git branch colors
        git0: '#8484a8',  // Gray for trunk/main
        git1: '#fbbf24',  // Amber/yellow for feature-base
        git2: '#c084fc',  // Purple for features
        git3: '#22d3ee',  // Cyan for features
        git4: '#ec4899',  // Pink/magenta for features
        git5: '#a855f7',  // Deep purple for features
        git6: '#10b981',  // Green for features
        git7: '#f59e0b',  // Orange for features
        gitBranchLabel0: '#0a0a14',
        gitBranchLabel1: '#0a0a14',
        gitBranchLabel2: '#0a0a14',
        gitBranchLabel3: '#0a0a14',
        gitBranchLabel4: '#0a0a14',
        gitBranchLabel5: '#0a0a14',
        gitBranchLabel6: '#0a0a14',
        gitBranchLabel7: '#0a0a14',
        gitInv0: '#0a0a14',
        gitInv1: '#0a0a14',
        gitInv2: '#0a0a14',
        gitInv3: '#0a0a14',
        gitInv4: '#0a0a14',
        gitInv5: '#0a0a14',
        gitInv6: '#0a0a14',
        gitInv7: '#0a0a14',
        commitLabelColor: '#d4d4e8',
        commitLabelBackground: '#1a1a3e',
        commitLabelFontSize: '16px',
        tagLabelColor: '#0a0a14',
        tagLabelBackground: '#22d3ee',
        tagLabelBorder: '#06b6d4',
        tagLabelFontSize: '14px',
      },
    });

    if (ref.current) {
      const id = `mermaid-${Math.random().toString(36).substr(2, 9)}`;
      ref.current.innerHTML = '';
      
      mermaid.render(id, chart).then(({ svg }) => {
        if (ref.current) {
          ref.current.innerHTML = svg;
        }
      }).catch((error) => {
        console.error('Mermaid rendering error:', error);
        if (ref.current) {
          ref.current.innerHTML = `<pre style="color: #ec4899;">Error rendering diagram: ${error.message}</pre>`;
        }
      });
    }
  }, [chart]);

  return (
    <div 
      ref={ref} 
      className="my-6 flex justify-center overflow-x-auto rounded-lg border border-gray-700 bg-gray-900 p-6"
      style={{
        backgroundColor: '#13132b',
        borderColor: '#252547',
      }}
    />
  );
}
