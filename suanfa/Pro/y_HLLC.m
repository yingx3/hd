function F=y_HLLC(hL,uL,vL,hR,uR,vR)

    global g db

    [m,n]=size(hL);

    F=zeros(m,n,3);

    vg=1/2.*(vL+vR)+sqrt(g.*hL)-sqrt(g.*hR);
    hg=(1/2.*(sqrt(g.*hL)+sqrt(g.*hR))+1/4.*(vL-vR)).^2./g;

    cmL=sqrt(g.*hL);
    cmR=sqrt(g.*hR);
    cmg=sqrt(g.*hg);

    sL=min(vL-cmL,vg-cmg);
    sR=max(vR+cmR,vg+cmg);

    I=hL<=db&hR>db;sL(I)=vR(I)-2.*sqrt(g.*hR(I));sR(I)=vR(I)+sqrt(g.*hR(I));
    I=hR<=db&hL>db;sR(I)=vL(I)+2.*sqrt(g.*hL(I));sL(I)=vL(I)-sqrt(g.*hL(I));

    sG=(sL.*hR.*(vR-sR)-sR.*hL.*(vL-sL))./(hR.*(vR-sR)-hL.*(vL-sL));
    sG(hL<=db&hR>db)=sL(hL<=db&hR>db);
    sG(hR<=db&hL>db)=sR(hR<=db&hL>db);

    FL(:,:,1)=hL.*vL;
    FL(:,:,2)=hL.*uL.*vL;
    FL(:,:,3)=hL.*vL.^2+1/2.*g.*hL.^2;

    FR(:,:,1)=hR.*vR;
    FR(:,:,2)=hR.*uR.*vR;
    FR(:,:,3)=hR.*vR.^2+1/2.*g.*hR.^2;

    M1=F(:,:,1);M2=F(:,:,2);M3=F(:,:,3);
    M11=FL(:,:,1);M12=FL(:,:,2);M13=FL(:,:,3);
    M21=FR(:,:,1);M22=FR(:,:,2);M23=FR(:,:,3);
    M31=hL;M33=hL.*vL;
    M41=hR;M43=hR.*vR;

    K1=(sR.*M11-sL.*M21+sR.*sL.*(M41-M31))./(sR-sL);

    K3=(sR.*M13-sL.*M23+sR.*sL.*(M43-M33))./(sR-sL);

    I=sL>=0;M1(I)=M11(I);M2(I)=M12(I);M3(I)=M13(I);
    I=sL<=0&sG>=0;M1(I)=K1(I);M2(I)=K1(I).*uL(I);M3(I)=K3(I);
    I=sG<=0&sR>=0;M1(I)=K1(I);M2(I)=K1(I).*uR(I);M3(I)=K3(I);
    I=sR<=0;M1(I)=M21(I);M2(I)=M22(I);M3(I)=M23(I);

    F(:,:,1)=M1;F(:,:,2)=M2;F(:,:,3)=M3;