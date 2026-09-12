function F=x_HLLC(hL,uL,vL,hR,uR,vR)

    global g db

    [m,n]=size(hL);

    F=zeros(m,n,3);

    ug=1/2.*(uL+uR)+sqrt(g.*hL)-sqrt(g.*hR);
    hg=(1/2.*(sqrt(g.*hL)+sqrt(g.*hR))+1/4.*(uL-uR)).^2./g;

    cmL=sqrt(g.*hL);
    cmR=sqrt(g.*hR);
    cmg=sqrt(g.*hg);

    sL=min(uL-cmL,ug-cmg);
    sR=max(uR+cmR,ug+cmg);

    I=hL<=db&hR>db;sL(I)=uR(I)-2.*sqrt(g.*hR(I));sR(I)=uR(I)+sqrt(g.*hR(I));
    I=hR<=db&hL>db;sR(I)=uL(I)+2.*sqrt(g.*hL(I));sL(I)=uL(I)-sqrt(g.*hL(I));

    sG=(sL.*hR.*(uR-sR)-sR.*hL.*(uL-sL))./(hR.*(uR-sR)-hL.*(uL-sL));
    sG(hL<=db&hR>db)=sL(hL<=db&hR>db);
    sG(hR<=db&hL>db)=sR(hR<=db&hL>db);

    FL(:,:,1)=hL.*uL;
    FL(:,:,2)=hL.*uL.^2+1/2.*g.*hL.^2;
    FL(:,:,3)=hL.*uL.*vL;

    FR(:,:,1)=hR.*uR;
    FR(:,:,2)=hR.*uR.^2+1/2.*g.*hR.^2;
    FR(:,:,3)=hR.*uR.*vR;

    M1=F(:,:,1);M2=F(:,:,2);M3=F(:,:,3);
    M11=FL(:,:,1);M12=FL(:,:,2);M13=FL(:,:,3);
    M21=FR(:,:,1);M22=FR(:,:,2);M23=FR(:,:,3);
    M31=hL;M32=hL.*uL;
    M41=hR;M42=hR.*uR;

    K1=(sR.*M11-sL.*M21+sR.*sL.*(M41-M31))./(sR-sL);
    K2=(sR.*M12-sL.*M22+sR.*sL.*(M42-M32))./(sR-sL);


    I=sL>=0;M1(I)=M11(I);M2(I)=M12(I);M3(I)=M13(I);
    I=sL<=0&sG>=0;M1(I)=K1(I);M2(I)=K2(I);M3(I)=K1(I).*vL(I);
    I=sG<=0&sR>=0;M1(I)=K1(I);M2(I)=K2(I);M3(I)=K1(I).*vR(I);
    I=sR<=0;M1(I)=M21(I);M2(I)=M22(I);M3(I)=M23(I);

    F(:,:,1)=M1;F(:,:,2)=M2;F(:,:,3)=M3;